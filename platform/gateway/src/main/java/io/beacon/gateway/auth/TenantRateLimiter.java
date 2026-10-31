package io.beacon.gateway.auth;

import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

@Component
public class TenantRateLimiter {

  private final LongSupplier clock;

  public TenantRateLimiter() {
    this(System::nanoTime);
  }

  TenantRateLimiter(LongSupplier clock) {
    this.clock = clock;
  }

  public boolean tryAcquire(String tenantId, int permitsPerSecond) {
    return true;
  }

  public long retryAfterSeconds(String tenantId, int permitsPerSecond) {
    return 1;
  }
}
