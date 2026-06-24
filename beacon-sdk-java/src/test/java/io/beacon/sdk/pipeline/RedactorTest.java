package io.beacon.sdk.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.beacon.sdk.config.BeaconConfigLoader;
import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.record.LogRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RedactorTest {

  private SdkMetrics metrics;

  @BeforeEach
  void setUp() {
    metrics = new SdkMetrics();
  }

  private static LogRecord recordWith(Map<String, Object> attrs) {
    return LogRecord.builder()
        .timestamp(Instant.parse("2026-06-20T00:00:00Z"))
        .severityNumber(9)
        .severityText("INFO")
        .body("hello")
        .resource(Map.of("service.name", "t", "telemetry.sdk.language", "java"))
        .attributes(attrs)
        .build();
  }

  private Redactor redactorFor(Set<String> keysLower, long timeoutMs) {
    return new Redactor(keysLower, timeoutMs, metrics);
  }

  @Test
  void redacts_top_level_matching_key_preserves_field() {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("password", "hunter2");
    attrs.put("order.id", 9921);
    LogRecord in = recordWith(attrs);

    LogRecord out = redactorFor(Set.of("password"), 100L).redact(in);

    assertThat(out.attributes()).hasSize(2);
    assertThat(out.attributes()).containsEntry("password", "[REDACTED]");
    assertThat(out.attributes()).containsEntry("order.id", 9921);
  }

  @Test
  void case_insensitive_ascii_match() {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("PASSWORD", "a");
    attrs.put("PaSsWoRd", "b"); // overwritten? Use a distinct key instead.
    // LinkedHashMap rejects duplicates by overwrite; use two distinct mixed-case keys:
    attrs.clear();
    attrs.put("PASSWORD", "a");
    attrs.put("Password", "b");
    attrs.put("name", "alice");
    LogRecord in = recordWith(attrs);

    LogRecord out = redactorFor(Set.of("password"), 100L).redact(in);

    assertThat(out.attributes()).containsEntry("PASSWORD", "[REDACTED]");
    assertThat(out.attributes()).containsEntry("Password", "[REDACTED]");
    assertThat(out.attributes()).containsEntry("name", "alice");
  }

  @Test
  void recurses_into_nested_maps() {
    Map<String, Object> user = new LinkedHashMap<>();
    user.put("password", "x");
    user.put("name", "alice");
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("user", user);
    LogRecord in = recordWith(attrs);

    LogRecord out = redactorFor(Set.of("password"), 100L).redact(in);

    @SuppressWarnings("unchecked")
    Map<String, Object> outUser = (Map<String, Object>) out.attributes().get("user");
    assertThat(outUser).containsEntry("password", "[REDACTED]");
    assertThat(outUser).containsEntry("name", "alice");
  }

  @Test
  void recurses_into_list_of_maps() {
    Map<String, Object> h1 = new LinkedHashMap<>();
    h1.put("authorization", "Bearer x");
    Map<String, Object> h2 = new LinkedHashMap<>();
    h2.put("x-other", "y");
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("headers", List.of(h1, h2));
    LogRecord in = recordWith(attrs);

    LogRecord out = redactorFor(Set.of("authorization"), 100L).redact(in);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> headers = (List<Map<String, Object>>) out.attributes().get("headers");
    assertThat(headers).hasSize(2);
    assertThat(headers.get(0)).containsEntry("authorization", "[REDACTED]");
    assertThat(headers.get(1)).containsEntry("x-other", "y");
  }

  @Test
  void does_not_mutate_input_and_preserves_identity_when_no_match() {
    Map<String, Object> attrs = Map.of("order.id", 9921, "name", "alice");
    LogRecord in = recordWith(attrs);

    LogRecord out = redactorFor(Set.of("password"), 100L).redact(in);

    // identity-preserved: no change → no allocation, same record reference
    assertThat(out).isSameAs(in);
    assertThat(out.attributes()).isSameAs(attrs);
  }

  @Test
  void body_string_passed_through_unchanged() {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("password", "secret");
    LogRecord in =
        LogRecord.builder()
            .timestamp(Instant.parse("2026-06-20T00:00:00Z"))
            .severityNumber(9)
            .severityText("INFO")
            .body("{\"password\":\"hunter2\"}")
            .resource(Map.of("service.name", "t", "telemetry.sdk.language", "java"))
            .attributes(attrs)
            .build();

    LogRecord out = redactorFor(Set.of("password"), 100L).redact(in);

    // body field is untouched; only attributes change
    assertThat(out.body()).isSameAs(in.body());
    assertThat(out.attributes()).containsEntry("password", "[REDACTED]");
  }

  @Test
  void deadline_exceeded_throws_RedactorTimeoutException_and_increments_counter() {
    // Build a 35-level nested map (exceeds MAX_DEPTH=32 → DeadlineExceeded fallback path)
    Map<String, Object> leaf = new HashMap<>();
    leaf.put("k", "v");
    Map<String, Object> nested = leaf;
    for (int i = 0; i < 40; i++) {
      Map<String, Object> wrap = new HashMap<>();
      wrap.put("child", nested);
      nested = wrap;
    }
    LogRecord in = recordWith(nested);
    Redactor r = redactorFor(Set.of("password"), 100L);

    assertThatThrownBy(() -> r.redact(in))
        .isInstanceOf(RedactorTimeoutException.class)
        .satisfies(
            ex -> {
              RedactorTimeoutException rte = (RedactorTimeoutException) ex;
              assertThat(rte.original()).isSameAs(in);
            });
    assertThat(metrics.redactorTimeouts()).isEqualTo(1L);
  }

  @Test
  void adversarial_long_key_short_circuits_safely() {
    // 1 MB key with effective keys = {"password"}; comparator must finish well under 5 ms.
    StringBuilder sb = new StringBuilder(1_000_000);
    for (int i = 0; i < 1_000_000; i++) sb.append('x');
    Map<String, Object> attrs = Map.of(sb.toString(), "v", "name", "alice");
    LogRecord in = recordWith(attrs);
    Redactor r = redactorFor(Set.of("password"), 5L);

    assertThatCode(() -> r.redact(in)).doesNotThrowAnyException();
  }

  @Test
  void defaults_baseline_keys_redact_when_user_supplies_none() {
    Set<String> effective = BeaconConfigLoader.effectiveRedactKeys(List.of(), true);
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("api_key", "x");
    attrs.put("other", "y");
    LogRecord in = recordWith(attrs);

    LogRecord out = new Redactor(effective, 100L, metrics).redact(in);

    assertThat(out.attributes()).containsEntry("api_key", "[REDACTED]");
    assertThat(out.attributes()).containsEntry("other", "y");
  }
}
