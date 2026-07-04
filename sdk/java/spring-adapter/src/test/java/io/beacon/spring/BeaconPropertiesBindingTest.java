package io.beacon.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.sdk.config.BeaconConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the 13 canonical {@code beacon.*} surfaces from {@code application.yml}-style property
 * sources through {@link BeaconProperties#toBeaconConfig()} into the frozen 15-component {@link
 * BeaconConfig} record. Includes the regression guard for the ADR-0009 §3 Option-A fold (deprecated
 * top-level {@code beacon.redactor-timeout-ms} must NOT bind).
 *
 * <p>Uses {@link ApplicationContextRunner} so the test exercises Spring Boot's real relaxed-binding
 * machinery (kebab-case → camelCase, nested composite) without spinning up a full application
 * context.
 */
class BeaconPropertiesBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              org.springframework.boot.autoconfigure.AutoConfigurations.of(
                  PropertyPlaceholderAutoConfiguration.class))
          .withUserConfiguration(EnableBeaconPropertiesConfig.class);

  @Configuration
  @EnableConfigurationProperties(BeaconProperties.class)
  static class EnableBeaconPropertiesConfig {}

  @Test
  void bindsAll13CanonicalSurfacesFromApplicationProperties() {
    runner
        .withPropertyValues(
            "beacon.endpoint=http://x:4317",
            "beacon.api-key=k",
            "beacon.buffer-capacity=42",
            "beacon.drop-policy=SPILL_FALLBACK",
            "beacon.batch-max-records=64",
            "beacon.flush-interval-ms=250",
            "beacon.max-retries=2",
            "beacon.backoff-base-ms=10",
            "beacon.backoff-max-ms=100",
            "beacon.fallback-sink=stderr",
            "beacon.shutdown-drain-timeout-ms=200",
            "beacon.sampling-ratio=0.5",
            "beacon.redact.keys=ssn,authorization",
            "beacon.redact.defaults=false",
            "beacon.redact.timeout-ms=7")
        .run(
            ctx -> {
              BeaconProperties p = ctx.getBean(BeaconProperties.class);
              BeaconConfig c = p.toBeaconConfig();

              // 12 leaf surfaces
              assertThat(c.endpoint()).isEqualTo("http://x:4317");
              assertThat(c.apiKey()).isEqualTo("k");
              assertThat(c.bufferCapacity()).isEqualTo(42);
              assertThat(c.dropPolicy()).isEqualTo(BeaconConfig.DropPolicy.SPILL_FALLBACK);
              assertThat(c.batchMaxRecords()).isEqualTo(64);
              assertThat(c.flushIntervalMs()).isEqualTo(250L);
              assertThat(c.maxRetries()).isEqualTo(2);
              assertThat(c.backoffBaseMs()).isEqualTo(10L);
              assertThat(c.backoffMaxMs()).isEqualTo(100L);
              assertThat(c.fallbackSink()).isEqualTo("stderr");
              assertThat(c.shutdownDrainTimeoutMs()).isEqualTo(200L);
              assertThat(c.samplingRatio()).isEqualTo(0.5);

              // Composite redact → 3 internal record components
              assertThat(c.redactKeys()).containsExactlyInAnyOrder("ssn", "authorization");
              assertThat(c.redactDefaults()).isFalse();
              assertThat(c.redactorTimeoutMs())
                  .as("beacon.redact.timeout-ms must map to internal redactorTimeoutMs slot")
                  .isEqualTo(7L);
            });
  }

  /**
   * Regression guard for ADR-0009 §3 Option-A: the legacy top-level key {@code
   * beacon.redactor-timeout-ms} (M1.6's pre-fold surface) must NOT bind. The starter exposes the
   * value only via the nested {@code beacon.redact.timeout-ms}.
   */
  @Test
  void rejectsTopLevelRedactorTimeoutMsKey() {
    runner
        .withPropertyValues(
            "beacon.endpoint=http://x:4317",
            "beacon.redactor-timeout-ms=99" // ← deprecated; MUST NOT bind
            )
        .run(
            ctx -> {
              BeaconProperties p = ctx.getBean(BeaconProperties.class);
              BeaconConfig c = p.toBeaconConfig();
              assertThat(c.redactorTimeoutMs())
                  .as(
                      "deprecated top-level beacon.redactor-timeout-ms must not bind; "
                          + "internal redactorTimeoutMs stays at default 5L")
                  .isEqualTo(5L);
            });
  }

  @Test
  void defaultsMatchBeaconConfigDefaults() {
    runner
        .withPropertyValues(
            "beacon.endpoint=http://x:4317"
            // everything else left at defaults
            )
        .run(
            ctx -> {
              BeaconProperties p = ctx.getBean(BeaconProperties.class);
              BeaconConfig c = p.toBeaconConfig();
              BeaconConfig d = BeaconConfig.defaults();

              assertThat(c.bufferCapacity()).isEqualTo(d.bufferCapacity());
              assertThat(c.dropPolicy()).isEqualTo(d.dropPolicy());
              assertThat(c.batchMaxRecords()).isEqualTo(d.batchMaxRecords());
              assertThat(c.flushIntervalMs()).isEqualTo(d.flushIntervalMs());
              assertThat(c.maxRetries()).isEqualTo(d.maxRetries());
              assertThat(c.backoffBaseMs()).isEqualTo(d.backoffBaseMs());
              assertThat(c.backoffMaxMs()).isEqualTo(d.backoffMaxMs());
              assertThat(c.fallbackSink()).isEqualTo(d.fallbackSink());
              assertThat(c.shutdownDrainTimeoutMs()).isEqualTo(d.shutdownDrainTimeoutMs());
              assertThat(c.samplingRatio()).isEqualTo(d.samplingRatio());
              assertThat(c.redactKeys()).isEqualTo(d.redactKeys());
              assertThat(c.redactDefaults()).isEqualTo(d.redactDefaults());
              assertThat(c.redactorTimeoutMs()).isEqualTo(d.redactorTimeoutMs());
            });
  }
}
