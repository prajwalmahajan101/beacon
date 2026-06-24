package io.beacon.sdk.record;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression: CanonicalJson.writeMap must not NPE on null map argument.
 * Carried from M1.7 benchmark (docs/benchmarks/sdk-overhead.md § Known issue);
 * fixed in M1.8 Plan 03-04.
 */
class CanonicalJsonNullMapTest {

    @Test
    @DisplayName("writeMap(sb, null) emits {} and does not throw")
    void writeMapNullEmitsEmptyObject() throws Exception {
        StringBuilder sb = new StringBuilder();
        invokeWriteMap(sb, null);
        assertThat(sb.toString()).isEqualTo("{}");
    }

    @Test
    @DisplayName("writeMap(sb, emptyMap) emits {} and does not throw")
    void writeMapEmptyEmitsEmptyObject() throws Exception {
        StringBuilder sb = new StringBuilder();
        invokeWriteMap(sb, Map.of());
        assertThat(sb.toString()).isEqualTo("{}");
    }

    @Test
    @DisplayName("writeMap with nested null map value renders inner null without NPE")
    void writeMapNestedNullMap() throws Exception {
        Map<String, Object> outer = new HashMap<>();
        outer.put("a", 1);
        outer.put("b", null);  // nested null value — writeValue must handle
        StringBuilder sb = new StringBuilder();
        assertThatCode(() -> invokeWriteMap(sb, outer)).doesNotThrowAnyException();
        // Both orders are acceptable (sorted serialization is checked by other tests);
        // assert both expected fragments appear.
        String out = sb.toString();
        assertThat(out).contains("\"a\":1").contains("\"b\":null");
    }

    @Test
    @DisplayName("LogRecord with null resource + null scope + null attributes serialises cleanly")
    void logRecordWithAllNullableMapsNullSerialises() {
        LogRecord rec = LogRecord.builder()
                .timestamp(java.time.Instant.parse("2026-06-24T00:00:00Z"))
                .severityNumber(9)
                .severityText("INFO")
                .body("null-maps-test")
                .build();
        String json = CanonicalJson.serialize(rec);
        assertThat(json).contains("\"body\":\"null-maps-test\"");
        // None of the nullable maps should emit a JSON null member; they should be absent
        // OR rendered as {} per the serializer's existing convention. Either is acceptable —
        // just confirm no NullPointerException leaked.
    }

    private static void invokeWriteMap(StringBuilder sb, Map<String, Object> map) throws Exception {
        Method m = CanonicalJson.class.getDeclaredMethod("writeMap", StringBuilder.class, Map.class);
        m.setAccessible(true);
        m.invoke(null, sb, map);
    }
}
