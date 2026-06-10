package io.beacon.sdk.exporter;

import io.beacon.sdk.record.LogRecord;

import java.util.List;

/**
 * OTLP exporter (gRPC + HTTP). Wraps OTel Java's OtlpGrpcLogRecordExporter /
 * OtlpHttpLogRecordExporter with Beacon's resilience layer ({@link RetryPolicy} +
 * {@link FallbackSink}). See spec/02 §2.4, §2.5.
 * Implemented in M1.4.
 */
public final class OtlpExporter {

    public enum Transport { GRPC, HTTP }

    private final String endpoint;
    private final Transport transport;

    public OtlpExporter(String endpoint, Transport transport) {
        this.endpoint = endpoint;
        this.transport = transport;
    }

    public void export(List<LogRecord> batch) {
        throw new UnsupportedOperationException("M1.4: OTLP exporter");
    }
}
