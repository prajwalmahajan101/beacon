package io.beacon.sdk.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  @Test
  void rejects_invalid_arguments() {
    assertThatThrownBy(() -> new RetryPolicy(-1, 10, 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryPolicy(3, 0, 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryPolicy(3, 100, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void delay_respects_ceiling_per_attempt() {
    RetryPolicy p = new RetryPolicy(5, 100, 5_000);
    // attempt 0 -> ceiling = 100; 1 -> 200; 2 -> 400; 3 -> 800; 4 -> 1600; 5 -> 3200; 6 -> 5000
    // (clamped)
    long[] ceilings = {100, 200, 400, 800, 1600, 3200, 5000};
    for (int attempt = 0; attempt < ceilings.length; attempt++) {
      for (int trial = 0; trial < 50; trial++) {
        long d = p.nextDelayMs(attempt);
        assertThat(d)
            .as("attempt %d delay must be 0..%d", attempt, ceilings[attempt])
            .isBetween(0L, ceilings[attempt]);
      }
    }
  }

  @Test
  void delay_clamps_at_maxMs_for_large_attempts() {
    RetryPolicy p = new RetryPolicy(5, 100, 1_000);
    for (int trial = 0; trial < 100; trial++) {
      assertThat(p.nextDelayMs(40)).isBetween(0L, 1_000L);
      assertThat(p.nextDelayMs(Integer.MAX_VALUE)).isBetween(0L, 1_000L);
    }
  }

  @Test
  void negative_attempt_treated_as_zero() {
    RetryPolicy p = new RetryPolicy(5, 100, 5_000);
    for (int trial = 0; trial < 50; trial++) {
      assertThat(p.nextDelayMs(-7)).isBetween(0L, 100L);
    }
  }

  @Test
  void getters_expose_configuration() {
    RetryPolicy p = new RetryPolicy(7, 50, 2_000);
    assertThat(p.maxRetries()).isEqualTo(7);
    assertThat(p.baseMs()).isEqualTo(50);
    assertThat(p.maxMs()).isEqualTo(2_000);
  }
}
