package io.beacon.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Unit tests for {@link BeaconConfigLoader}.
 *
 * <p>Java cannot mutate {@link System#getenv()} at runtime. The env-precedence assertion lives in
 * {@link #env_wins_over_sysprop_and_builder_when_present()} which is annotated with {@link
 * EnabledIfEnvironmentVariable} and is skipped unless {@code BEACON_REDACT_KEYS} is set externally
 * (e.g. in CI). All other precedence layers (sysprop, builder) are exercised on every run.
 */
class BeaconConfigLoaderTest {

  @BeforeEach
  @AfterEach
  void clearSysprops() {
    System.clearProperty(BeaconConfigLoader.SYSPROP_REDACT_KEYS);
    System.clearProperty(BeaconConfigLoader.SYSPROP_REDACTOR_TIMEOUT_MS);
    System.clearProperty(BeaconConfigLoader.SYSPROP_REDACT_DEFAULTS);
  }

  // ─── redactKeys: precedence + parsing ─────────────────────────────────────

  @Test
  void resolveRedactKeys_falls_back_to_builder_when_unset() {
    List<String> builder = List.of("custom_one", "custom_two");
    assertThat(BeaconConfigLoader.resolveRedactKeys(builder)).isEqualTo(builder);
  }

  @Test
  void resolveRedactKeys_returns_empty_list_when_builder_is_null_and_unset() {
    assertThat(BeaconConfigLoader.resolveRedactKeys(null)).isEmpty();
  }

  @Test
  void resolveRedactKeys_sysprop_overrides_builder() {
    System.setProperty(BeaconConfigLoader.SYSPROP_REDACT_KEYS, "password,ssn,authorization");
    assertThat(BeaconConfigLoader.resolveRedactKeys(List.of("ignored")))
        .containsExactly("password", "ssn", "authorization");
  }

  @Test
  void resolveRedactKeys_sysprop_comma_split_trims_whitespace_and_drops_blanks() {
    System.setProperty(BeaconConfigLoader.SYSPROP_REDACT_KEYS, "password, ssn ,  authorization, ,");
    assertThat(BeaconConfigLoader.resolveRedactKeys(List.of()))
        .containsExactly("password", "ssn", "authorization");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "BEACON_REDACT_KEYS", matches = ".+")
  void env_wins_over_sysprop_and_builder_when_present() {
    System.setProperty(BeaconConfigLoader.SYSPROP_REDACT_KEYS, "from_sysprop");
    // Env wins; the resolved list should match the externally-set env-var, not the sysprop.
    List<String> resolved = BeaconConfigLoader.resolveRedactKeys(List.of("from_builder"));
    assertThat(resolved).doesNotContain("from_sysprop", "from_builder");
  }

  // ─── redactorTimeoutMs: precedence + bad-input fallback ───────────────────

  @Test
  void resolveRedactorTimeoutMs_falls_back_to_builder_when_unset() {
    assertThat(BeaconConfigLoader.resolveRedactorTimeoutMs(7L)).isEqualTo(7L);
  }

  @Test
  void resolveRedactorTimeoutMs_sysprop_overrides_builder() {
    System.setProperty(BeaconConfigLoader.SYSPROP_REDACTOR_TIMEOUT_MS, "42");
    assertThat(BeaconConfigLoader.resolveRedactorTimeoutMs(5L)).isEqualTo(42L);
  }

  @Test
  void resolveRedactorTimeoutMs_malformed_sysprop_warns_and_falls_back() {
    System.setProperty(BeaconConfigLoader.SYSPROP_REDACTOR_TIMEOUT_MS, "abc");
    assertThat(BeaconConfigLoader.resolveRedactorTimeoutMs(5L)).isEqualTo(5L);
  }

  // ─── redactDefaults: precedence + bad-input fallback ──────────────────────

  @Test
  void resolveRedactDefaults_falls_back_to_builder_when_unset() {
    assertThat(BeaconConfigLoader.resolveRedactDefaults(true)).isTrue();
    assertThat(BeaconConfigLoader.resolveRedactDefaults(false)).isFalse();
  }

  @Test
  void resolveRedactDefaults_sysprop_overrides_builder_case_insensitive() {
    System.setProperty(BeaconConfigLoader.SYSPROP_REDACT_DEFAULTS, "FALSE");
    assertThat(BeaconConfigLoader.resolveRedactDefaults(true)).isFalse();
    System.setProperty(BeaconConfigLoader.SYSPROP_REDACT_DEFAULTS, "True");
    assertThat(BeaconConfigLoader.resolveRedactDefaults(false)).isTrue();
  }

  @Test
  void resolveRedactDefaults_malformed_sysprop_warns_and_falls_back() {
    System.setProperty(BeaconConfigLoader.SYSPROP_REDACT_DEFAULTS, "maybe");
    assertThat(BeaconConfigLoader.resolveRedactDefaults(true)).isTrue();
    assertThat(BeaconConfigLoader.resolveRedactDefaults(false)).isFalse();
  }

  // ─── effectiveRedactKeys: union + lowercase under Locale.ROOT ─────────────

  @Test
  void effectiveRedactKeys_includes_baseline_when_includeDefaults_true() {
    Set<String> eff = BeaconConfigLoader.effectiveRedactKeys(List.of("ssn"), true);
    assertThat(eff).contains("password", "authorization", "api_key", "secret", "token", "ssn");
  }

  @Test
  void effectiveRedactKeys_excludes_baseline_when_includeDefaults_false() {
    Set<String> eff = BeaconConfigLoader.effectiveRedactKeys(List.of("ssn"), false);
    assertThat(eff).containsExactly("ssn");
  }

  @Test
  void effectiveRedactKeys_lowercases_under_Locale_ROOT() {
    // "PASSWORD" must collapse to "password"; Turkish-I-safe (no Locale.getDefault()).
    Set<String> eff =
        BeaconConfigLoader.effectiveRedactKeys(List.of("PASSWORD", "X-Api-Key"), false);
    assertThat(eff).containsExactly("password", "x-api-key");
  }

  // ─── applyOverrides: layered builder + sysprop ────────────────────────────

  @Test
  void applyOverrides_layers_sysprop_on_top_of_base() {
    System.setProperty(BeaconConfigLoader.SYSPROP_REDACTOR_TIMEOUT_MS, "12");
    System.setProperty(BeaconConfigLoader.SYSPROP_REDACT_DEFAULTS, "false");
    System.setProperty(BeaconConfigLoader.SYSPROP_REDACT_KEYS, "foo,bar");

    BeaconConfig out = BeaconConfigLoader.applyOverrides(BeaconConfig.defaults());

    assertThat(out.redactorTimeoutMs()).isEqualTo(12L);
    assertThat(out.redactDefaults()).isFalse();
    assertThat(out.redactKeys()).containsExactly("foo", "bar");
  }

  @Test
  void applyOverrides_returns_base_unchanged_when_no_overrides_set() {
    BeaconConfig base = BeaconConfig.defaults();
    BeaconConfig out = BeaconConfigLoader.applyOverrides(base);
    assertThat(out.redactorTimeoutMs()).isEqualTo(base.redactorTimeoutMs());
    assertThat(out.redactDefaults()).isEqualTo(base.redactDefaults());
    assertThat(out.redactKeys()).isEqualTo(base.redactKeys());
  }
}
