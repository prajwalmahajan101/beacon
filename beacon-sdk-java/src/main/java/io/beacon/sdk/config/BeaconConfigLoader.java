package io.beacon.sdk.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads M1.6-introduced SDK config values from environment variables, system properties, and
 * builder-supplied defaults, with precedence (highest wins):
 *
 * <ol>
 *   <li>Environment variable ({@code BEACON_*})
 *   <li>System property ({@code -Dbeacon.*})
 *   <li>Builder value (typically a {@link BeaconConfig} field)
 *   <li>Hard-coded default (only when builder value is {@code null})
 * </ol>
 *
 * <p>The SDK must NEVER crash on a malformed env var / sysprop. Bad input is logged at {@code WARN}
 * via SLF4J and the next precedence layer is consulted.
 *
 * <p>See ADR-0007 for the rationale behind env-first ordering and the always-on baseline of redact
 * keys ({@code password|authorization|api_key|secret|token}).
 */
public final class BeaconConfigLoader {

  private static final Logger LOG = LoggerFactory.getLogger(BeaconConfigLoader.class);

  /** Always-on baseline of redact keys, lowercase ASCII. Immutable; do not mutate. */
  static final Set<String> DEFAULT_REDACT_KEYS =
      Set.of("password", "authorization", "api_key", "secret", "token");

  static final String ENV_REDACT_KEYS = "BEACON_REDACT_KEYS";
  static final String ENV_REDACTOR_TIMEOUT_MS = "BEACON_REDACTOR_TIMEOUT_MS";
  static final String ENV_REDACT_DEFAULTS = "BEACON_REDACT_DEFAULTS";

  static final String SYSPROP_REDACT_KEYS = "beacon.redact_keys";
  static final String SYSPROP_REDACTOR_TIMEOUT_MS = "beacon.redactor_timeout_ms";
  static final String SYSPROP_REDACT_DEFAULTS = "beacon.redact_defaults";

  // ─── Reserved canonical env/sysprop spellings (M1.8 Plan 03-01) ──────────
  // These constants exist so the M1.8 cross-SDK contract test (ConfigKeysContractTest)
  // can assert every canonical spelling in beacon-s0-contract/conformance/config-keys.yaml
  // appears verbatim in SDK source — even for keys whose builder wiring (env > sysprop >
  // builder resolver) is deferred to M2 hygiene work. The names below match
  // config-keys.yaml exactly. Do NOT remove without updating the contract artifact.

  static final String ENV_ENDPOINT = "BEACON_ENDPOINT";
  static final String SYSPROP_ENDPOINT = "beacon.endpoint";
  static final String ENV_API_KEY = "BEACON_API_KEY";
  static final String SYSPROP_API_KEY = "beacon.api-key";
  static final String ENV_BUFFER_CAPACITY = "BEACON_BUFFER_CAPACITY";
  static final String SYSPROP_BUFFER_CAPACITY = "beacon.buffer-capacity";
  static final String ENV_DROP_POLICY = "BEACON_DROP_POLICY";
  static final String SYSPROP_DROP_POLICY = "beacon.drop-policy";
  static final String ENV_BATCH_MAX_RECORDS = "BEACON_BATCH_MAX_RECORDS";
  static final String SYSPROP_BATCH_MAX_RECORDS = "beacon.batch-max-records";
  static final String ENV_FLUSH_INTERVAL_MS = "BEACON_FLUSH_INTERVAL_MS";
  static final String SYSPROP_FLUSH_INTERVAL_MS = "beacon.flush-interval-ms";
  static final String ENV_MAX_RETRIES = "BEACON_MAX_RETRIES";
  static final String SYSPROP_MAX_RETRIES = "beacon.max-retries";
  static final String ENV_BACKOFF_BASE_MS = "BEACON_BACKOFF_BASE_MS";
  static final String SYSPROP_BACKOFF_BASE_MS = "beacon.backoff-base-ms";
  static final String ENV_BACKOFF_MAX_MS = "BEACON_BACKOFF_MAX_MS";
  static final String SYSPROP_BACKOFF_MAX_MS = "beacon.backoff-max-ms";
  static final String ENV_FALLBACK_SINK = "BEACON_FALLBACK_SINK";
  static final String SYSPROP_FALLBACK_SINK = "beacon.fallback-sink";
  static final String ENV_SHUTDOWN_DRAIN_TIMEOUT_MS = "BEACON_SHUTDOWN_DRAIN_TIMEOUT_MS";
  static final String SYSPROP_SHUTDOWN_DRAIN_TIMEOUT_MS = "beacon.shutdown-drain-timeout-ms";
  static final String ENV_SAMPLING_RATIO = "BEACON_SAMPLING_RATIO";
  static final String SYSPROP_SAMPLING_RATIO = "beacon.sampling-ratio";

  private BeaconConfigLoader() {
    // utility class
  }

  /**
   * Resolve {@code redact_keys} from env &gt; sysprop &gt; builder. Comma-separated lists are
   * split, each element trimmed, blanks dropped. Returns the builder value (or {@link List#of()} if
   * {@code null}) when neither env nor sysprop is set.
   */
  public static List<String> resolveRedactKeys(List<String> builderValue) {
    String env = System.getenv(ENV_REDACT_KEYS);
    if (env != null && !env.isBlank()) return parseList(env);
    String sys = System.getProperty(SYSPROP_REDACT_KEYS);
    if (sys != null && !sys.isBlank()) return parseList(sys);
    return builderValue != null ? builderValue : List.of();
  }

  /**
   * Resolve {@code redactor_timeout_ms} from env &gt; sysprop &gt; builder. A malformed value logs
   * {@code WARN} and falls through to the next layer (never throws).
   */
  public static long resolveRedactorTimeoutMs(long builderValue) {
    String env = System.getenv(ENV_REDACTOR_TIMEOUT_MS);
    Long parsed = parseLongOrWarn(env, ENV_REDACTOR_TIMEOUT_MS);
    if (parsed != null) return parsed;
    String sys = System.getProperty(SYSPROP_REDACTOR_TIMEOUT_MS);
    parsed = parseLongOrWarn(sys, SYSPROP_REDACTOR_TIMEOUT_MS);
    if (parsed != null) return parsed;
    return builderValue;
  }

  /**
   * Resolve {@code redact_defaults} from env &gt; sysprop &gt; builder. Case-insensitive {@code
   * true}/{@code false}; anything else logs {@code WARN} and falls through (never throws).
   */
  public static boolean resolveRedactDefaults(boolean builderValue) {
    String env = System.getenv(ENV_REDACT_DEFAULTS);
    Boolean parsed = parseBooleanOrWarn(env, ENV_REDACT_DEFAULTS);
    if (parsed != null) return parsed;
    String sys = System.getProperty(SYSPROP_REDACT_DEFAULTS);
    parsed = parseBooleanOrWarn(sys, SYSPROP_REDACT_DEFAULTS);
    if (parsed != null) return parsed;
    return builderValue;
  }

  /**
   * Build the effective set of redact keys: union of (defaults if {@code includeDefaults}) and
   * {@code resolved}, every element lowercased under {@link Locale#ROOT} for case-insensitive ASCII
   * matching (Turkish-I-safe).
   */
  public static Set<String> effectiveRedactKeys(List<String> resolved, boolean includeDefaults) {
    Set<String> out = new HashSet<>();
    if (includeDefaults) out.addAll(DEFAULT_REDACT_KEYS);
    if (resolved != null) {
      for (String k : resolved) {
        if (k != null && !k.isBlank()) out.add(k.toLowerCase(Locale.ROOT));
      }
    }
    Set<String> normalized = new HashSet<>(out.size());
    for (String k : out) normalized.add(k.toLowerCase(Locale.ROOT));
    return normalized;
  }

  /**
   * Layer env/sysprop overrides on top of {@code base} using the {@code withX} builders for the
   * three M1.6 keys ({@code redactKeys}, {@code redactorTimeoutMs}, {@code redactDefaults}). Wired
   * into {@code BeaconSdk.Builder.build()} in plan 04.
   */
  public static BeaconConfig applyOverrides(BeaconConfig base) {
    BeaconConfig out = base;
    out = out.withRedactKeys(resolveRedactKeys(out.redactKeys()));
    out = out.withRedactorTimeoutMs(resolveRedactorTimeoutMs(out.redactorTimeoutMs()));
    out = out.withRedactDefaults(resolveRedactDefaults(out.redactDefaults()));
    return out;
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private static List<String> parseList(String raw) {
    List<String> out = new ArrayList<>();
    for (String piece : Arrays.asList(raw.split(","))) {
      String trimmed = piece.trim();
      if (!trimmed.isEmpty()) out.add(trimmed);
    }
    return out;
  }

  private static Long parseLongOrWarn(String raw, String source) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException nfe) {
      LOG.warn(
          "Beacon: ignoring malformed long for {}={} (falling back to next precedence layer)",
          source,
          raw);
      return null;
    }
  }

  private static Boolean parseBooleanOrWarn(String raw, String source) {
    if (raw == null || raw.isBlank()) return null;
    String v = raw.trim().toLowerCase(Locale.ROOT);
    if ("true".equals(v)) return Boolean.TRUE;
    if ("false".equals(v)) return Boolean.FALSE;
    LOG.warn(
        "Beacon: ignoring malformed boolean for {}={} (falling back to next precedence layer)",
        source,
        raw);
    return null;
  }
}
