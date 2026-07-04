package io.beacon.sdk.exporter;

import io.beacon.sdk.config.BeaconConfig;
import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.record.CanonicalJson;
import io.beacon.sdk.record.LogRecord;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * Receives batches the exporter could not deliver. See spec/02 §2.5.
 *
 * <p>{@link #write(List)} appends one canonical-JSON record per line and increments {@code
 * SdkMetrics.fallback_writes} by the batch size. Two impls: {@link StderrFallbackSink} (default,
 * writes to {@code System.err}) and {@link FileFallbackSink} (append-only file). Selected via
 * {@link #fromConfig} based on {@link BeaconConfig#fallbackSink()} — {@code "stderr"} for stderr,
 * {@code "file:<path>"} for a file target.
 */
public interface FallbackSink {

  /** Append every record in {@code batch} as one canonical-JSON line. */
  void write(List<LogRecord> batch);

  static FallbackSink fromConfig(BeaconConfig config, SdkMetrics metrics) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(metrics, "metrics");
    String spec = config.fallbackSink();
    if (spec == null || spec.isBlank() || "stderr".equalsIgnoreCase(spec)) {
      return new StderrFallbackSink(metrics);
    }
    if (spec.startsWith("file:")) {
      return new FileFallbackSink(Path.of(spec.substring("file:".length())), metrics);
    }
    throw new IllegalArgumentException(
        "unsupported fallback_sink: '" + spec + "' (expected 'stderr' or 'file:<path>')");
  }

  /** Writes each record as one JSON line to {@code System.err}. */
  final class StderrFallbackSink implements FallbackSink {
    private final SdkMetrics metrics;
    private final PrintStream err;

    public StderrFallbackSink(SdkMetrics metrics) {
      this(metrics, System.err);
    }

    StderrFallbackSink(SdkMetrics metrics, PrintStream err) {
      this.metrics = Objects.requireNonNull(metrics, "metrics");
      this.err = Objects.requireNonNull(err, "err");
    }

    @Override
    public void write(List<LogRecord> batch) {
      for (LogRecord r : batch) {
        err.println(CanonicalJson.serialize(r));
      }
      metrics.incFallbackWrite(batch.size());
    }
  }

  /** Append-only file sink, one JSON line per record. UTF-8, sync per batch. */
  final class FileFallbackSink implements FallbackSink {
    private final SdkMetrics metrics;
    private final Path path;

    public FileFallbackSink(Path path, SdkMetrics metrics) {
      this.path = Objects.requireNonNull(path, "path");
      this.metrics = Objects.requireNonNull(metrics, "metrics");
      try {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
      } catch (IOException e) {
        throw new UncheckedIOException("failed to prepare fallback dir " + path, e);
      }
    }

    @Override
    public void write(List<LogRecord> batch) {
      try (BufferedWriter w =
          Files.newBufferedWriter(
              path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
        for (LogRecord r : batch) {
          w.write(CanonicalJson.serialize(r));
          w.newLine();
        }
      } catch (IOException e) {
        throw new UncheckedIOException("fallback file write failed: " + path, e);
      }
      metrics.incFallbackWrite(batch.size());
    }
  }
}
