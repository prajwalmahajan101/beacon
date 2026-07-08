package io.beacon.gateway.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drives {@link RecordValidator} against the contract's canonical example fixtures (single source
 * of truth, located via the {@code beacon.contract.examples} system property set by the build).
 */
class RecordValidatorTest {

  private static final Path EXAMPLES = Path.of(System.getProperty("beacon.contract.examples"));

  private final RecordValidator validator = new RecordValidator();

  @Test
  void validExamplePasses() throws IOException {
    String json = Files.readString(EXAMPLES.resolve("log-valid.json"));
    assertThat(validator.validate(json)).isEmpty();
  }

  @ParameterizedTest(name = "{0} is rejected")
  @MethodSource("invalidFixtures")
  void invalidExamplesAreRejectedWithReasons(Path fixture) throws IOException {
    List<String> reasons = validator.validate(Files.readString(fixture));
    assertThat(reasons)
        .as("fixture %s must be rejected with at least one reason", fixture)
        .isNotEmpty();
  }

  @Test
  void malformedJsonIsRejected() {
    assertThat(validator.validate("{ not json")).isNotEmpty();
  }

  static Stream<Path> invalidFixtures() throws IOException {
    // The single top-level invalid example plus every fixture in the invalid/ dir.
    Stream<Path> nested;
    try (var s = Files.list(EXAMPLES.resolve("invalid"))) {
      nested = s.sorted().toList().stream();
    }
    return Stream.concat(Stream.of(EXAMPLES.resolve("log-invalid.json")), nested);
  }
}
