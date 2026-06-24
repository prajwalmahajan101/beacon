package io.beacon.sdk.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.beacon.sdk.config.BeaconConfig;
import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.record.LogRecord;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FallbackSinkTest {

  private static LogRecord rec(int i) {
    return LogRecord.minimal(
        Instant.parse("2026-06-12T00:00:00Z").plusMillis(i),
        9,
        "INFO",
        "rec-" + i,
        Map.of("service.name", "t", "telemetry.sdk.language", "java"));
  }

  @Test
  void stderr_sink_writes_one_json_line_per_record_and_increments_metric() {
    SdkMetrics m = new SdkMetrics();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    FallbackSink.StderrFallbackSink sink =
        new FallbackSink.StderrFallbackSink(m, new PrintStream(buf, true, StandardCharsets.UTF_8));

    sink.write(List.of(rec(1), rec(2), rec(3)));

    String[] lines = buf.toString(StandardCharsets.UTF_8).split("\\R");
    assertThat(lines).hasSize(3);
    for (String line : lines) {
      assertThat(line).startsWith("{").endsWith("}");
    }
    assertThat(m.fallbackWrites()).isEqualTo(3);
  }

  @Test
  void file_sink_appends_one_json_line_per_record(@TempDir Path tmp) throws Exception {
    SdkMetrics m = new SdkMetrics();
    Path path = tmp.resolve("fallback.log");
    FallbackSink.FileFallbackSink sink = new FallbackSink.FileFallbackSink(path, m);

    sink.write(List.of(rec(1), rec(2)));
    sink.write(List.of(rec(3)));

    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    assertThat(lines).hasSize(3);
    for (String line : lines) {
      assertThat(line).startsWith("{").endsWith("}");
    }
    assertThat(m.fallbackWrites()).isEqualTo(3);
  }

  @Test
  void fromConfig_defaults_to_stderr() {
    SdkMetrics m = new SdkMetrics();
    FallbackSink sink = FallbackSink.fromConfig(BeaconConfig.defaults(), m);
    assertThat(sink).isInstanceOf(FallbackSink.StderrFallbackSink.class);
  }

  @Test
  void fromConfig_selects_file_for_file_prefix(@TempDir Path tmp) {
    SdkMetrics m = new SdkMetrics();
    BeaconConfig fileCfg =
        BeaconConfig.defaults().withFallbackSink("file:" + tmp.resolve("out.log"));

    FallbackSink sink = FallbackSink.fromConfig(fileCfg, m);
    assertThat(sink).isInstanceOf(FallbackSink.FileFallbackSink.class);
  }

  @Test
  void fromConfig_rejects_unknown_spec() {
    SdkMetrics m = new SdkMetrics();
    BeaconConfig badCfg = BeaconConfig.defaults().withFallbackSink("kafka:foo");

    assertThatThrownBy(() -> FallbackSink.fromConfig(badCfg, m))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kafka:foo");
  }
}
