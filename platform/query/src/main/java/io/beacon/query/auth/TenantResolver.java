package io.beacon.query.auth;

import io.beacon.query.config.QueryProperties;
import io.beacon.query.config.QueryProperties.Tenant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class TenantResolver {

  private final Map<String, Tenant> byApiKey;

  public TenantResolver(QueryProperties properties) {
    this.byApiKey =
        properties.getTenants().stream()
            .collect(Collectors.toUnmodifiableMap(Tenant::getApiKey, Function.identity()));
  }

  public Optional<Tenant> resolve(String apiKey) {
    if (apiKey == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byApiKey.get(apiKey));
  }

  public boolean isAuthEnabled() {
    return !byApiKey.isEmpty();
  }
}
