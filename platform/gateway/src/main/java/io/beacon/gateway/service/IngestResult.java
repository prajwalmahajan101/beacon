package io.beacon.gateway.service;

import java.util.List;

/**
 * Outcome of ingesting one OTLP export request.
 *
 * @param accepted number of records that mapped, validated, and were durably produced
 * @param rejected number of records rejected (un-mappable or schema-invalid)
 * @param reasons ordered rejection reasons (mapping + validation failures), for the OTLP {@code
 *     partial_success.error_message}
 * @param kafkaFailed true if the durable produce failed/timed out — the transport returns 5xx /
 *     {@code UNAVAILABLE} so the SDK's fallback engages (INGEST-04)
 */
public record IngestResult(int accepted, int rejected, List<String> reasons, boolean kafkaFailed) {

  /** A single human-readable message summarising every rejection, for the OTLP partial success. */
  public String rejectionMessage() {
    return String.join("; ", reasons);
  }
}
