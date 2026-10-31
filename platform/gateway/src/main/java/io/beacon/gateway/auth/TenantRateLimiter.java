package io.beacon.gateway.auth;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

@Component
public class TenantRateLimiter {

  private final LongSupplier clock;
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  public TenantRateLimiter() {
    this(System::nanoTime);
  }

  TenantRateLimiter(LongSupplier clock) {
    this.clock = clock;
  }

  public boolean tryAcquire(String tenantId, int permitsPerSecond) {
    Bucket bucket =
        buckets.computeIfAbsent(tenantId, k -> new Bucket(permitsPerSecond, clock.getAsLong()));
    refill(bucket, permitsPerSecond);
    long current = bucket.tokens.get();
    while (current > 0) {
      if (bucket.tokens.compareAndSet(current, current - 1)) {
        return true;
      }
      current = bucket.tokens.get();
    }
    return false;
  }

  public long retryAfterSeconds(String tenantId, int permitsPerSecond) {
    return 1;
  }

  private void refill(Bucket bucket, int permitsPerSecond) {
    long now = clock.getAsLong();
    long elapsed = now - bucket.lastRefillNanos;
    if (elapsed <= 0) {
      return;
    }
    long newTokens = elapsed * permitsPerSecond / 1_000_000_000L;
    if (newTokens > 0) {
      long updated = Math.min(bucket.tokens.get() + newTokens, permitsPerSecond);
      bucket.tokens.set(updated);
      bucket.lastRefillNanos = now;
    }
  }

  private static class Bucket {
    final AtomicLong tokens;
    volatile long lastRefillNanos;

    Bucket(int initialTokens, long nowNanos) {
      this.tokens = new AtomicLong(initialTokens);
      this.lastRefillNanos = nowNanos;
    }
  }
}
