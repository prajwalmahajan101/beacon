package io.beacon.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TenantRateLimiterTest {

  private final AtomicLong clock = new AtomicLong(0);
  private final TenantRateLimiter limiter = new TenantRateLimiter(clock::get);

  @Test
  void allowsRequestsUnderLimit() {
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire("t1", 10)).isTrue();
    }
  }

  @Test
  void rejectsWhenBucketExhausted() {
    for (int i = 0; i < 10; i++) {
      limiter.tryAcquire("t1", 10);
    }
    assertThat(limiter.tryAcquire("t1", 10)).isFalse();
  }

  @Test
  void refillsAfterOneSecond() {
    for (int i = 0; i < 10; i++) {
      limiter.tryAcquire("t1", 10);
    }
    assertThat(limiter.tryAcquire("t1", 10)).isFalse();

    clock.addAndGet(1_000_000_000L);
    assertThat(limiter.tryAcquire("t1", 10)).isTrue();
  }

  @Test
  void separateBucketsPerTenant() {
    for (int i = 0; i < 10; i++) {
      limiter.tryAcquire("t1", 10);
    }
    assertThat(limiter.tryAcquire("t1", 10)).isFalse();
    assertThat(limiter.tryAcquire("t2", 10)).isTrue();
  }

  @Test
  void retryAfterIsPositiveWhenExhausted() {
    for (int i = 0; i < 10; i++) {
      limiter.tryAcquire("t1", 10);
    }
    assertThat(limiter.retryAfterSeconds("t1", 10)).isGreaterThan(0);
  }
}
