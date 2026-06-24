package io.beacon.spring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.beacon.sdk.BeaconSdk;
import io.beacon.sdk.appender.BeaconLogbackAppender;
import io.beacon.sdk.config.BeaconConfig;
import io.beacon.sdk.pipeline.BatchSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wires {@link BeaconAutoConfiguration} through {@link ApplicationContextRunner} and
 * asserts the four contributed beans, the {@code beacon.enabled=false} opt-out gate,
 * and {@code @ConditionalOnMissingBean} precedence for user overrides. Also asserts
 * the {@link BeaconLogbackAppender} is programmatically attached to the root Logback
 * logger (Pitfall #18 — no {@code logback-spring.xml} mutation).
 */
class BeaconAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BeaconAutoConfiguration.class));

    @AfterEach
    void detachAppender() {
        // Root Logger is a JVM-wide singleton. Strip any "beacon" appender left
        // from auto-config so it doesn't bleed into other tests.
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
            Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
            root.detachAppender("beacon");
        }
    }

    @Test
    void wiringRegistersBeaconSdkBean() {
        runner.withPropertyValues("beacon.endpoint=http://localhost:4317").run(ctx -> {
            assertThat(ctx).hasSingleBean(BeaconSdk.class);
            BeaconSdk sdk = ctx.getBean(BeaconSdk.class);
            assertThat(sdk.config().endpoint()).isEqualTo("http://localhost:4317");
        });
    }

    @Test
    void wiringRegistersBeaconLogbackAppenderAndAttachesItToRoot() {
        runner.withPropertyValues("beacon.endpoint=http://localhost:4317").run(ctx -> {
            assertThat(ctx).hasSingleBean(BeaconLogbackAppender.class);

            LoggerContext lctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            Logger root = lctx.getLogger(Logger.ROOT_LOGGER_NAME);
            assertThat(root.getAppender("beacon"))
                    .as("BeaconLogbackAppender must be programmatically attached to root")
                    .isNotNull();
        });
    }

    @Test
    void wiringRegistersBeaconTaskDecorator() {
        runner.withPropertyValues("beacon.endpoint=http://localhost:4317").run(ctx -> {
            assertThat(ctx).hasBean("beaconTaskDecorator");
            BeaconTaskDecorator decorator = ctx.getBean("beaconTaskDecorator", BeaconTaskDecorator.class);
            Runnable raw = () -> { /* no-op */ };
            Runnable wrapped = decorator.decorate(raw);
            assertThat(wrapped)
                    .as("BeaconExecutors.wrap returns a wrapping Runnable, not the raw one")
                    .isNotSameAs(raw);
        });
    }

    @Test
    void beaconDisabledSkipsAllBeans() {
        runner.withPropertyValues(
                "beacon.enabled=false",
                "beacon.endpoint=http://localhost:4317"
        ).run(ctx -> {
            assertThat(ctx).doesNotHaveBean(BeaconSdk.class);
            assertThat(ctx).doesNotHaveBean(BeaconLogbackAppender.class);
            assertThat(ctx).doesNotHaveBean(BeaconTaskDecorator.class);
        });
    }

    @Test
    void userBeaconSdkBeanWins() {
        runner.withPropertyValues("beacon.endpoint=http://localhost:4317")
                .withUserConfiguration(UserSdkConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(BeaconSdk.class);
                    BeaconSdk sdk = ctx.getBean(BeaconSdk.class);
                    // User config sets a sentinel endpoint distinct from the auto-config one.
                    assertThat(sdk.config().endpoint()).isEqualTo("user://override");
                });
    }

    @Test
    void userTaskDecoratorByNameWins() {
        runner.withPropertyValues("beacon.endpoint=http://localhost:4317")
                .withUserConfiguration(UserDecoratorConfig.class)
                .run(ctx -> {
                    // User's named bean wins; the starter's BeaconTaskDecorator type bean is absent.
                    assertThat(ctx).hasBean("beaconTaskDecorator");
                    Object bean = ctx.getBean("beaconTaskDecorator");
                    assertThat(bean).isInstanceOf(TaskDecorator.class);
                    assertThat(bean).isNotInstanceOf(BeaconTaskDecorator.class);
                });
    }

    @Configuration
    static class UserSdkConfig {
        @Bean(destroyMethod = "close")
        BeaconSdk userBeaconSdk() {
            BeaconConfig cfg = BeaconConfig.defaults().withBufferCapacity(8);
            // Sentinel endpoint to prove this bean — not the auto-config one — was picked.
            BeaconConfig override = new BeaconConfig(
                    "user://override", null,
                    cfg.bufferCapacity(), cfg.dropPolicy(), cfg.batchMaxRecords(),
                    cfg.flushIntervalMs(), cfg.maxRetries(), cfg.backoffBaseMs(),
                    cfg.backoffMaxMs(), cfg.fallbackSink(), cfg.shutdownDrainTimeoutMs(),
                    cfg.redactKeys(), cfg.samplingRatio(), cfg.redactorTimeoutMs(),
                    cfg.redactDefaults()
            );
            return BeaconSdk.builder().config(override).sink(BatchSink.NOOP).build();
        }
    }

    @Configuration
    static class UserDecoratorConfig {
        @Bean(name = "beaconTaskDecorator")
        TaskDecorator beaconTaskDecorator() {
            return r -> r;   // identity — proves user's named bean wins
        }
    }
}
