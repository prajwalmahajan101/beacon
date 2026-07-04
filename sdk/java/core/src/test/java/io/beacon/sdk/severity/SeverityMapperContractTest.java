package io.beacon.sdk.severity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link SeverityMapper} to the cross-SDK contract artifact
 * (contract/spec/severity-table.json). Pitfall #4 — cross-SDK severity-table divergence guard, Java
 * side. M2 (Python SDK) will mirror this test against the same artifact.
 */
class SeverityMapperContractTest {

  private static final Path CONTRACT_JSON =
      repoRoot().resolve(Paths.get("contract", "spec", "severity-table.json"));

  /**
   * Walk up from the test working directory (the module dir) until the repo root — the ancestor
   * containing {@code contract/} — is found. Robust to the module's depth under the root (M2.9
   * moved this module to {@code sdk/java/core}; see docs/adr/0022).
   */
  private static Path repoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("contract"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException(
          "Could not locate repo root (a parent containing contract) from "
              + Paths.get("").toAbsolutePath());
    }
    return dir;
  }

  @Test
  @DisplayName(
      "contract: severity-table.json defines exactly 6 bands with anchors [1,5,9,13,17,21]")
  void sixBandsWithSpecAnchors() throws Exception {
    JsonNode bands = loadContract().get("bands");
    assertThat(bands.size()).isEqualTo(6);
    int[] expected = {1, 5, 9, 13, 17, 21};
    for (int i = 0; i < 6; i++) {
      assertThat(bands.get(i).get("anchor").asInt())
          .as("band[%d].anchor", i)
          .isEqualTo(expected[i]);
    }
  }

  @Test
  @DisplayName("contract: SeverityMapper.textFor(anchor) returns band.text for every band")
  void mapperAnchorTextMatchesContract() throws Exception {
    JsonNode bands = loadContract().get("bands");
    for (JsonNode band : bands) {
      int anchor = band.get("anchor").asInt();
      String expectedText = band.get("text").asText();
      assertThat(SeverityMapper.textFor(anchor))
          .as("textFor(%d) matches contract band.text", anchor)
          .isEqualTo(expectedText);
    }
  }

  @Test
  @DisplayName("contract: off-anchor numbers in [range_min..range_max] all collapse to band.text")
  void offAnchorCollapseMatchesContract() throws Exception {
    JsonNode bands = loadContract().get("bands");
    for (JsonNode band : bands) {
      int rmin = band.get("range_min").asInt();
      int rmax = band.get("range_max").asInt();
      String expectedText = band.get("text").asText();
      for (int n = rmin; n <= rmax; n++) {
        assertThat(SeverityMapper.textFor(n))
            .as("textFor(%d) within band %s collapses to anchor text", n, expectedText)
            .isEqualTo(expectedText);
      }
    }
  }

  @Test
  @DisplayName("contract: numberFor(band.name) returns the contract anchor")
  void numberForReturnsContractAnchor() throws Exception {
    JsonNode bands = loadContract().get("bands");
    for (JsonNode band : bands) {
      String name = band.get("name").asText();
      int anchor = band.get("anchor").asInt();
      assertThat(SeverityMapper.numberFor(name)).as("numberFor(%s)", name).isEqualTo(anchor);
    }
  }

  private static JsonNode loadContract() throws Exception {
    assertThat(Files.exists(CONTRACT_JSON))
        .as("contract file %s must exist (Plan 03-02 Task 1)", CONTRACT_JSON)
        .isTrue();
    return new ObjectMapper().readTree(CONTRACT_JSON.toFile());
  }
}
