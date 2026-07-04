package io.beacon.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the Java SDK's effective config-key set to the cross-SDK contract artifact
 * (contract/conformance/config-keys.yaml). Pitfall #3 — cross-language config-key drift guard, Java
 * side.
 *
 * <p>For each canonical surface in the YAML:
 *
 * <ul>
 *   <li>A matching BeaconConfig record component exists (mapping kebab-case → camelCase, with the
 *       composite {@code redact} resolving to the three internal slots redactKeys / redactDefaults
 *       / redactorTimeoutMs per ADR-0009 §3).
 *   <li>The env-var spelling appears literally somewhere under beacon-sdk-java/src/main (no
 *       hardcoded drift inside the SDK).
 *   <li>The sysprop spelling appears literally somewhere under beacon-sdk-java/src/main.
 * </ul>
 */
class ConfigKeysContractTest {

  private static final Path CONTRACT_YAML =
      repoRoot().resolve(Paths.get("contract", "conformance", "config-keys.yaml"));

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

  private static final Path SDK_MAIN =
      Paths.get("src", "main", "java").toAbsolutePath().normalize();

  /** kebab-case → camelCase; "redact.keys" → "redactKeys" via composite mapping. */
  private static final Map<String, String> COMPOSITE_CHILD_TO_COMPONENT =
      Map.of(
          "redact.keys", "redactKeys",
          "redact.defaults", "redactDefaults",
          "redact.timeout-ms", "redactorTimeoutMs");

  @Test
  @DisplayName("contract: surface count is 13 (12 leaf + 1 composite)")
  void canonicalSurfaceCountIs13() throws Exception {
    Map<String, Object> doc = loadContract();
    assertThat(doc).containsEntry("canonical_surface_count", 13);
  }

  @Test
  @DisplayName("contract: every canonical key maps to a BeaconConfig record component")
  @SuppressWarnings("unchecked")
  void everyContractKeyMapsToBeaconConfigComponent() throws Exception {
    Map<String, Object> doc = loadContract();
    List<Map<String, Object>> keys = (List<Map<String, Object>>) doc.get("keys");

    Set<String> componentNames =
        Arrays.stream(BeaconConfig.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(Collectors.toSet());

    for (Map<String, Object> key : keys) {
      String name = (String) key.get("name");
      String parent = (String) key.get("nested_of");
      String type = (String) key.get("type");

      if ("composite".equals(type)) continue; // parent placeholder

      String component;
      if (parent != null) {
        component = COMPOSITE_CHILD_TO_COMPONENT.get(parent + "." + name);
        assertThat(component).as("composite child %s.%s mapped", parent, name).isNotNull();
      } else {
        component = kebabToCamel(name);
      }
      assertThat(componentNames)
          .as("BeaconConfig record component for canonical key '%s' (-> %s)", name, component)
          .contains(component);
    }
  }

  @Test
  @DisplayName("contract: env/sysprop spellings appear literally in SDK source")
  @SuppressWarnings("unchecked")
  void envAndSyspropSpellingsExistInSdkSource() throws Exception {
    Map<String, Object> doc = loadContract();
    List<Map<String, Object>> keys = (List<Map<String, Object>>) doc.get("keys");
    String allSources = readAllSdkSource();

    for (Map<String, Object> key : keys) {
      String name = (String) key.get("name");
      String type = (String) key.get("type");
      if ("composite".equals(type)) continue; // parent placeholder

      String env = (String) key.get("env");
      String sysprop = (String) key.get("sysprop");

      // env spelling must appear somewhere in main source (a literal or named constant)
      assertThat(allSources)
          .as("env spelling '%s' for key '%s' must appear in SDK main source", env, name)
          .contains(env);

      // sysprop spelling must appear somewhere in main source
      assertThat(allSources)
          .as("sysprop spelling '%s' for key '%s' must appear in SDK main source", sysprop, name)
          .contains(sysprop);
    }
  }

  // --- helpers ---

  private static Map<String, Object> loadContract() throws IOException {
    assertThat(Files.exists(CONTRACT_YAML))
        .as("contract file %s must exist (Plan 03-01 Task 1)", CONTRACT_YAML)
        .isTrue();
    try (var in = Files.newInputStream(CONTRACT_YAML)) {
      return new Yaml().load(in);
    }
  }

  private static String readAllSdkSource() throws IOException {
    StringBuilder sb = new StringBuilder();
    try (Stream<Path> paths = Files.walk(SDK_MAIN)) {
      for (Path p : (Iterable<Path>) paths::iterator) {
        if (p.toString().endsWith(".java")) {
          sb.append(Files.readString(p)).append('\n');
        }
      }
    }
    return sb.toString();
  }

  private static String kebabToCamel(String s) {
    StringBuilder out = new StringBuilder();
    boolean upper = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '-') {
        upper = true;
        continue;
      }
      out.append(upper ? Character.toUpperCase(c) : c);
      upper = false;
    }
    return out.toString();
  }
}
