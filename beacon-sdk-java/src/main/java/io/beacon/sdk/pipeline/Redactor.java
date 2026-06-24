package io.beacon.sdk.pipeline;

import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.record.LogRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Production literal-key recursive redactor — replaces user-configured PII keys with the literal
 * {@code "[REDACTED]"} string before the record leaves the process (spec §2.7, scenario C10).
 *
 * <p>Design constraints (see ADR-0007):
 *
 * <ul>
 *   <li>Literal-key match only — no user regex API (ReDoS-immune by construction).
 *   <li>ASCII case-insensitive comparison via {@code Locale.ROOT} (Turkish-I-safe).
 *   <li>Full recursion through nested maps and lists; depth cap of {@value #MAX_DEPTH}.
 *   <li>Per-record deadline polled at every map entry / list element via {@link System#nanoTime()};
 *       on expiry: {@link SdkMetrics#incRedactorTimeout()} increments and a {@link
 *       RedactorTimeoutException} is thrown carrying the original record.
 *   <li>{@code body} (typed {@link String}) is opaque — body-string scrubbing is deferred per
 *       ADR-0007.
 *   <li>Input maps/lists are never mutated; new collections are built lazily only when the first
 *       change is detected (preserves identity for pure pass-through).
 * </ul>
 */
public final class Redactor {

  static final String REDACTED = "[REDACTED]";
  private static final int MAX_DEPTH = 32;

  /** Lowercased under {@link Locale#ROOT} by {@code BeaconConfigLoader.effectiveRedactKeys}. */
  private final Set<String> effectiveKeysLower;

  /**
   * Max length across {@link #effectiveKeysLower}; cheap length-based short-circuit before the O(n)
   * {@code toLowerCase} call. Keeps the 1 MB-key adversarial path well under budget.
   */
  private final int maxKeyLen;

  private final long timeoutNanos;
  private final SdkMetrics metrics;

  public Redactor(Set<String> effectiveKeysLower, long timeoutMs, SdkMetrics metrics) {
    this.effectiveKeysLower = Set.copyOf(Objects.requireNonNull(effectiveKeysLower));
    int max = 0;
    for (String k : this.effectiveKeysLower) {
      if (k.length() > max) max = k.length();
    }
    this.maxKeyLen = max;
    this.timeoutNanos = timeoutMs * 1_000_000L;
    this.metrics = Objects.requireNonNull(metrics);
  }

  public LogRecord redact(LogRecord in) {
    long start = System.nanoTime();
    long deadline = start + timeoutNanos;
    try {
      Map<String, Object> attrs = in.attributes();
      if (attrs == null) return in;
      Map<String, Object> redactedAttrs = walkMap(attrs, deadline, 0);
      // body (String) passed through unchanged per ADR-0007 deferral.
      if (redactedAttrs == attrs) return in; // no change → no copy
      return LogRecord.Builder.from(in).attributes(redactedAttrs).build();
    } catch (DeadlineExceeded de) {
      metrics.incRedactorTimeout();
      throw new RedactorTimeoutException(in, System.nanoTime() - start);
    }
  }

  /**
   * Walk a map and produce a redacted copy only if any value changed. Returns the input reference
   * (identity-preserved) when no entry matched and no child collection changed.
   */
  private Map<String, Object> walkMap(Map<String, Object> in, long deadline, int depth) {
    checkDepth(depth);
    Map<String, Object> out = null; // lazy allocate on first change
    for (Map.Entry<String, Object> e : in.entrySet()) {
      checkDeadline(deadline);
      String key = e.getKey();
      Object val = e.getValue();
      Object newVal;
      if (key != null && matches(key)) {
        newVal = REDACTED;
      } else if (val instanceof Map<?, ?> m) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) m;
        newVal = walkMap(typed, deadline, depth + 1);
      } else if (val instanceof List<?> l) {
        newVal = walkList(l, deadline, depth + 1);
      } else {
        newVal = val;
      }
      if (out == null && newVal != val) {
        // first change: snapshot prior entries into a fresh LinkedHashMap preserving order
        out = new LinkedHashMap<>(in.size());
        for (Map.Entry<String, Object> prior : in.entrySet()) {
          if (prior.getKey().equals(key)) break;
          out.put(prior.getKey(), prior.getValue());
        }
      }
      if (out != null) out.put(key, newVal);
    }
    return out != null ? out : in;
  }

  private List<Object> walkList(List<?> in, long deadline, int depth) {
    checkDepth(depth);
    List<Object> out = null;
    int i = 0;
    for (Object val : in) {
      checkDeadline(deadline);
      Object newVal;
      if (val instanceof Map<?, ?> m) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) m;
        newVal = walkMap(typed, deadline, depth + 1);
      } else if (val instanceof List<?> l) {
        newVal = walkList(l, deadline, depth + 1);
      } else {
        newVal = val;
      }
      if (out == null && newVal != val) {
        out = new ArrayList<>(in.size());
        int j = 0;
        for (Object prior : in) {
          if (j == i) break;
          out.add(prior);
          j++;
        }
      }
      if (out != null) out.add(newVal);
      i++;
    }
    // identity preservation: if we never allocated, return the input ref cast
    @SuppressWarnings("unchecked")
    List<Object> orig = (List<Object>) in;
    return out != null ? out : orig;
  }

  private boolean matches(String key) {
    // Length-based short-circuit: avoids the O(n) toLowerCase on adversarial long keys
    // (e.g. a 1 MB attribute key) where no effective target key can match by length.
    if (key.length() > maxKeyLen) return false;
    return effectiveKeysLower.contains(key.toLowerCase(Locale.ROOT));
  }

  private static void checkDeadline(long deadline) {
    if (System.nanoTime() > deadline) throw DeadlineExceeded.INSTANCE;
  }

  private static void checkDepth(int depth) {
    // treat over-depth as a deadline event — same fallback path
    if (depth > MAX_DEPTH) throw DeadlineExceeded.INSTANCE;
  }

  /** Internal sentinel — no stack trace, never escapes this class. */
  private static final class DeadlineExceeded extends RuntimeException {
    static final DeadlineExceeded INSTANCE = new DeadlineExceeded();

    private DeadlineExceeded() {
      super(null, null, false, false);
    }
  }
}
