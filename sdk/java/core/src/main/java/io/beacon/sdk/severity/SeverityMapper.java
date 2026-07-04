package io.beacon.sdk.severity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OTel severity band-anchor mapping per spec/01-telemetry-record-spec.md §1.1.
 *
 * <p>The band table is loaded from {@code beacon-s0-contract/spec/severity-table.json} at class
 * init (M1.8 Plan 03-02 — cross-SDK contract artifact). Public API (numberFor / textFor / bandFor)
 * is unchanged from M1.1; only the data source moved.
 *
 * <p>Resolution order for the artifact:
 *
 * <ol>
 *   <li>Classpath resource {@code /beacon-s0-contract/spec/severity-table.json} (works in test runs
 *       where the contract dir is added to the test resources).
 *   <li>Filesystem relative paths walked from common CWDs (conformance harness vs SDK gradle vs
 *       project root vs IDE run).
 * </ol>
 *
 * <p>If none resolve, the class init throws — fail-fast is the correct behaviour; the SDK is
 * unusable without a valid band table.
 */
public final class SeverityMapper {

  private SeverityMapper() {}

  public enum Band {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL;

    private int anchor;
    private int rangeMin;
    private int rangeMax;
    private String text;

    // Band's static init triggers SeverityMapper's static init, which populates
    // the per-constant fields below. This indirection is required because reading
    // e.g. SeverityMapper.Band.INFO.anchor() from another class would otherwise
    // load Band (the enclosing class is NOT init'd by enum-constant access per
    // JLS 12.4.1) and observe zero-initialized fields. Touching SeverityMapper.class
    // here forces the loader to run before any anchor()/text()/range*() read.
    static {
      try {
        Class.forName(SeverityMapper.class.getName());
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException("SeverityMapper class missing at Band init", e);
      }
    }

    public int anchor() {
      return anchor;
    }

    public int rangeMin() {
      return rangeMin;
    }

    public int rangeMax() {
      return rangeMax;
    }

    public String text() {
      return text;
    }
  }

  /** Ordered by ascending anchor — TRACE first, FATAL last. */
  private static final List<Band> BANDS_BY_ANCHOR;

  // The four Band instance fields (anchor, rangeMin, rangeMax, text) are populated
  // exactly once at class init by loadBandsFromContract() and never reassigned. This
  // is an unusual but accepted pattern for a closed enum populated from a contract
  // artifact — ADR-0010 (drafted in Plan 03-05) will record the trade-off.
  static {
    BANDS_BY_ANCHOR = loadBandsFromContract();
  }

  /** Look up the band-anchor number for a band name (e.g. {@code "WARN"} → 13). */
  public static int numberFor(String bandName) {
    return Band.valueOf(bandName).anchor;
  }

  /**
   * Resolve any severity number in the legal range (1–24) to the spec-enum text. Off-anchor inputs
   * collapse to the band anchor at or below (e.g. 18 → "ERROR", 14 → "WARN"). Required because the
   * schema enum restricts {@code severity_text} to the six band names.
   */
  public static String textFor(int otelNumber) {
    return bandFor(otelNumber).text;
  }

  /** Same resolution as {@link #textFor(int)} but returns the enum value. */
  public static Band bandFor(int otelNumber) {
    if (otelNumber < 1 || otelNumber > 24) {
      throw new IllegalArgumentException(
          "OTel severity_number must be in 1..24 (spec/01 §1.1); got " + otelNumber);
    }
    // BANDS_BY_ANCHOR is sorted ascending; walk from highest anchor downward.
    for (int i = BANDS_BY_ANCHOR.size() - 1; i >= 0; i--) {
      Band b = BANDS_BY_ANCHOR.get(i);
      if (otelNumber >= b.anchor) return b;
    }
    // Unreachable given the 1..24 guard above + a well-formed contract artifact
    // (which always defines TRACE at anchor=1).
    throw new IllegalStateException("severity-table.json missing TRACE band (anchor 1)");
  }

  // --- contract loader ---

  private static List<Band> loadBandsFromContract() {
    JsonNode root = readContractJson();
    JsonNode bandsNode = root.get("bands");
    if (bandsNode == null || !bandsNode.isArray() || bandsNode.size() != 6) {
      throw new IllegalStateException(
          "severity-table.json must define exactly 6 bands; got "
              + (bandsNode == null ? "null" : bandsNode.size()));
    }
    List<Band> out = new ArrayList<>(6);
    for (JsonNode n : bandsNode) {
      Band b = Band.valueOf(n.get("name").asText());
      b.anchor = n.get("anchor").asInt();
      b.rangeMin = n.get("range_min").asInt();
      b.rangeMax = n.get("range_max").asInt();
      b.text = n.get("text").asText();
      out.add(b);
    }
    out.sort((a, b) -> Integer.compare(a.anchor, b.anchor));
    // Sanity: contiguous 1..24 coverage.
    if (out.get(0).rangeMin != 1 || out.get(5).rangeMax != 24) {
      throw new IllegalStateException(
          "severity-table.json must cover the full 1..24 range contiguously");
    }
    return Collections.unmodifiableList(out);
  }

  private static JsonNode readContractJson() {
    ObjectMapper mapper = new ObjectMapper();
    String classpath = "/beacon-s0-contract/spec/severity-table.json";
    try (InputStream in = SeverityMapper.class.getResourceAsStream(classpath)) {
      if (in != null) return mapper.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("failed reading severity-table.json from classpath", e);
    }
    // Filesystem fallbacks — try common CWDs (conformance harness vs SDK gradle
    // vs project root vs deeper IDE run dirs).
    Path[] candidates =
        new Path[] {
          Paths.get("beacon-s0-contract", "spec", "severity-table.json"),
          Paths.get("..", "beacon-s0-contract", "spec", "severity-table.json"),
          Paths.get("..", "..", "beacon-s0-contract", "spec", "severity-table.json"),
          Paths.get("..", "..", "..", "beacon-s0-contract", "spec", "severity-table.json"),
        };
    for (Path p : candidates) {
      if (Files.exists(p)) {
        try (InputStream in = Files.newInputStream(p)) {
          return mapper.readTree(in);
        } catch (IOException e) {
          throw new IllegalStateException("failed reading " + p, e);
        }
      }
    }
    throw new IllegalStateException(
        "severity-table.json not found on classpath or relative filesystem paths "
            + java.util.Arrays.toString(candidates));
  }
}
