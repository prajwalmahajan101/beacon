package io.beacon.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Beacon ingest gateway (Phase 5.1, M3.0b).
 *
 * <p>The gateway is the inverse of the SDK: it receives standard OTLP {@code
 * ExportLogsServiceRequest}s (over gRPC on 4317 and HTTP on 4318), reconstructs the frozen M0 log
 * record, validates it against {@code contract/schema/log-record.schema.json}, and produces
 * canonical M0 JSON to Kafka. Invalid records are reported via OTLP {@code partial_success}; a
 * Kafka write failure surfaces as a 5xx / {@code UNAVAILABLE} so the SDK's fallback engages.
 *
 * @see io.beacon.gateway.service.IngestService
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BeaconGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(BeaconGatewayApplication.class, args);
  }
}
