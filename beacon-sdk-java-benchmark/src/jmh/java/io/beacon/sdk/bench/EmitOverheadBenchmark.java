package io.beacon.sdk.bench;

import io.beacon.sdk.BeaconSdk;
import io.beacon.sdk.config.BeaconConfig;
import io.beacon.sdk.pipeline.BatchSink;
import io.beacon.sdk.record.LogRecord;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the BeaconSdk.emit hot path (PRD NFR-6: emit p99 &lt; 1ms).
 *
 * <p>Workload: a precomputed LogRecord with a 16-byte body and 4 string
 * attributes; no MDC; no live OTel Span. The sink is BatchSink.NOOP, so the
 * pipeline measured is enricher.enrich → redactor.redact → buffer.offer.
 * Network I/O, OTel serialization, and the flusher thread are all out of scope
 * (the flusher runs asynchronously on a daemon thread and never blocks emit).
 *
 * <p>To reproduce locally: ./gradlew :beacon-sdk-java-benchmark:jmh
 * To reproduce in CI mode (faster, lower-resolution): ./gradlew :beacon-sdk-java-benchmark:jmh -PbenchmarkCI
 *
 * <p>Documented in docs/benchmarks/sdk-overhead.md.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class EmitOverheadBenchmark {

    private BeaconSdk sdk;
    private LogRecord record;

    @Setup(Level.Trial)
    public void setUp() {
        // Disable redaction defaults so the Redactor's baseline-key set is empty
        // and the redact() call is a no-op map walk on 4 keys. Realistic users
        // who configure redact_keys WILL pay more — called out in the report.
        BeaconConfig cfg = BeaconConfig.defaults().withRedactDefaults(false);
        sdk = BeaconSdk.builder().config(cfg).sink(BatchSink.NOOP).build();
        // Widen the literal to Map<String, Object> so it assigns to the
        // builder's attributes(Map<String, Object>) signature.
        Map<String, Object> attrs = Map.<String, Object>of(
                "a", "1", "b", "2", "c", "3", "d", "4");
        record = LogRecord.builder()
                .timestamp(Instant.now())
                .severityNumber(9)
                .severityText("INFO")
                .body("hello, beacon!!")
                .attributes(attrs)
                .build();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        sdk.close();
    }

    @Benchmark
    public void emit() {
        sdk.emit(record);
    }
}
