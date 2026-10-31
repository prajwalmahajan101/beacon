package io.beacon.gateway.auth;

import io.beacon.gateway.config.GatewayProperties;
import io.beacon.gateway.config.GatewayProperties.Tenant;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class TenantResolver {

  public TenantResolver(GatewayProperties properties) {}

  public Optional<Tenant> resolve(String apiKey) {
    return Optional.empty();
  }

  public boolean isAuthEnabled() {
    return false;
  }
}
