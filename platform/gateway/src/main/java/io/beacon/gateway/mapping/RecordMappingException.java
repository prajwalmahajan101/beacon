package io.beacon.gateway.mapping;

/**
 * Thrown when an OTLP {@code LogRecord} cannot be represented as a canonical M0 record at all —
 * i.e. it is missing a hard-required field that the canonical serializer dereferences ({@code
 * time_unix_nano}/{@code observed_time_unix_nano}, or {@code body}). The ingest path catches this
 * per record and tallies it as a rejection (OTLP {@code partial_success}), exactly like a
 * schema-validation failure. Schema-level invalidity (bad severity, missing resource attrs, bad id
 * patterns) is NOT reported here — those map cleanly and are caught downstream by {@code
 * RecordValidator}.
 */
public final class RecordMappingException extends RuntimeException {

  public RecordMappingException(String reason) {
    super(reason);
  }
}
