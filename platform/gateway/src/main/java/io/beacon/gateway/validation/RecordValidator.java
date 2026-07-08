package io.beacon.gateway.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Validates a canonical M0 JSON string against the frozen {@code
 * contract/schema/log-record.schema.json} (Draft 2020-12), bundled into the jar at build time under
 * {@code /schema/log-record.schema.json}.
 *
 * <p>The schema's {@code date-time}/hex-id constraints are enforced by explicit {@code pattern}s
 * (not {@code format}, which is annotation-only in Draft 2020-12), so no format-assertion opt-in is
 * needed. {@link #validate(String)} returns an ordered list of human-readable reasons — empty iff
 * the record is valid — which the ingest path tallies into the OTLP {@code partial_success}
 * response (INGEST-01).
 */
@Component
public final class RecordValidator {

  private static final String SCHEMA_RESOURCE = "/schema/log-record.schema.json";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JsonSchema schema;

  public RecordValidator() {
    this(RecordValidator.class.getResourceAsStream(SCHEMA_RESOURCE));
  }

  /** Package-visible for tests that inject an explicit schema stream. */
  RecordValidator(InputStream schemaStream) {
    Objects.requireNonNull(
        schemaStream,
        "schema stream is null — is " + SCHEMA_RESOURCE + " bundled on the classpath?");
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    this.schema = factory.getSchema(schemaStream);
  }

  /**
   * Validate a JSON document.
   *
   * @param json canonical M0 JSON to check
   * @return empty list iff the document is schema-valid; otherwise ordered, human-readable failure
   *     reasons (each prefixed with the failing instance location)
   */
  public List<String> validate(String json) {
    JsonNode node;
    try {
      node = MAPPER.readTree(json);
    } catch (Exception e) {
      return List.of("malformed JSON: " + e.getMessage());
    }
    Set<ValidationMessage> errors = schema.validate(node);
    return errors.stream()
        .map(m -> m.getInstanceLocation() + ": " + m.getMessage())
        .sorted()
        .collect(Collectors.toList());
  }
}
