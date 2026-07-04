package io.beacon.spring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.beacon.sdk.BeaconSdk;
import io.beacon.sdk.appender.BeaconLogbackAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot 3.x auto-configuration for Beacon — JSDK-07 / M1.7 Plan 02-02.
 *
 * <h2>Beans contributed</h2>
 *
 * <ol>
 *   <li>{@link BeaconProperties} — bound from {@code beacon.*} (via {@link
 *       EnableConfigurationProperties}).
 *   <li>{@link BeaconSdk} — built from {@code props.toBeaconConfig()}; closed on application
 *       context shutdown via {@code destroyMethod = "close"} (spec/02 §2.6 / C9).
 *   <li>{@link BeaconLogbackAppender} — instantiated, bound to the {@link BeaconSdk}, and
 *       programmatically attached to the root Logback {@link Logger} (no {@code logback-spring.xml}
 *       mutation — Pitfall #18). Defensive: if the SLF4J binding is NOT Logback (e.g. Log4j2), the
 *       appender is still returned but not attached; a WARN is logged.
 *   <li>{@link BeaconTaskDecorator} (named bean {@code beaconTaskDecorator}) — wraps async {@code
 *       Runnable}s with {@code BeaconExecutors.wrap} for OTel Context + MDC propagation (ADR-0008;
 *       Pitfall #2). Users wire it on their {@code ThreadPoolTaskExecutor} (see {@link
 *       BeaconTaskDecorator}).
 * </ol>
 *
 * <h2>Activation</h2>
 *
 * <ul>
 *   <li>{@code @ConditionalOnProperty(prefix="beacon", name="enabled", havingValue="true",
 *       matchIfMissing=true)} — opt-out via {@code beacon.enabled=false} (Pitfall #18).
 *   <li>{@code @ConditionalOnClass(BeaconSdk)} — defensive (the starter depends on the SDK so the
 *       class is always present; guards against shaded/relocated classpaths).
 *   <li>Each bean is {@code @ConditionalOnMissingBean} — users can override any of them.
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(BeaconProperties.class)
@ConditionalOnProperty(
    prefix = "beacon",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@ConditionalOnClass(name = "io.beacon.sdk.BeaconSdk")
public class BeaconAutoConfiguration {

  private static final org.slf4j.Logger LOG =
      LoggerFactory.getLogger(BeaconAutoConfiguration.class);

  /**
   * Build the singleton {@link BeaconSdk}. The SDK's own {@code Builder.build()} layers env +
   * sysprop overrides on top of the supplied config (precedence {@code env > sysprop >
   * application.yml}), preserving the M1.6 contract. {@code destroyMethod = "close"} hooks the bean
   * lifecycle to the graceful drain.
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  public BeaconSdk beaconSdk(BeaconProperties props) {
    return BeaconSdk.builder().config(props.toBeaconConfig()).build();
  }

  /**
   * Instantiate {@link BeaconLogbackAppender}, bind the SDK, and programmatically attach it to the
   * root Logback logger. Pitfall #18: NEVER mutates {@code logback-spring.xml} — attachment is
   * API-only via {@link LoggerContext}.
   *
   * <p>If the SLF4J binding is not Logback (e.g. {@code spring-boot-starter-log4j2}), the appender
   * is returned un-attached; the SDK still functions for programmatic {@code emit}, just not via
   * Logback.
   */
  @Bean
  @ConditionalOnMissingBean
  public BeaconLogbackAppender beaconLogbackAppender(BeaconSdk sdk) {
    BeaconLogbackAppender appender = new BeaconLogbackAppender();
    appender.setBeaconSdk(sdk);
    appender.setName("beacon");

    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory instanceof LoggerContext ctx) {
      appender.setContext(ctx);
      appender.start();
      Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
      root.addAppender(appender);
    } else {
      LOG.warn(
          "SLF4J binding is {} (not Logback) — BeaconLogbackAppender not "
              + "attached. Programmatic BeaconSdk.emit still works; Logback "
              + "bridge is disabled.",
          factory.getClass().getName());
    }
    return appender;
  }

  /**
   * Named bean {@code beaconTaskDecorator} — users wire it on their {@code
   * ThreadPoolTaskExecutor.setTaskDecorator(...)} to inherit OTel Context + MDC across async
   * boundaries (ADR-0008 / Pitfall #2). Spring does NOT auto-attach {@code TaskDecorator} beans by
   * type, so this is opt-in by name.
   */
  @Bean(name = "beaconTaskDecorator")
  @ConditionalOnMissingBean(name = "beaconTaskDecorator")
  public BeaconTaskDecorator beaconTaskDecorator() {
    return new BeaconTaskDecorator();
  }
}
