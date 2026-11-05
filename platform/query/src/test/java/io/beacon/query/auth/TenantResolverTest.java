package io.beacon.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.query.config.QueryProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantResolverTest {

  private static QueryProperties propsWithTenants(List<QueryProperties.Tenant> tenants) {
    var props = new QueryProperties();
    props.setTenants(tenants);
    return props;
  }

  private static QueryProperties.Tenant tenant(String apiKey, String tenantId) {
    var t = new QueryProperties.Tenant();
    t.setApiKey(apiKey);
    t.setTenantId(tenantId);
    return t;
  }

  @Test
  void resolvesKnownKey() {
    var resolver = new TenantResolver(propsWithTenants(List.of(tenant("key-1", "acme"))));
    var result = resolver.resolve("key-1");
    assertThat(result).isPresent();
    assertThat(result.get().getTenantId()).isEqualTo("acme");
  }

  @Test
  void returnsEmptyForUnknownKey() {
    var resolver = new TenantResolver(propsWithTenants(List.of(tenant("key-1", "acme"))));
    assertThat(resolver.resolve("bad-key")).isEmpty();
  }

  @Test
  void returnsEmptyForNull() {
    var resolver = new TenantResolver(propsWithTenants(List.of(tenant("key-1", "acme"))));
    assertThat(resolver.resolve(null)).isEmpty();
  }

  @Test
  void authDisabledWhenNoTenants() {
    var resolver = new TenantResolver(propsWithTenants(List.of()));
    assertThat(resolver.isAuthEnabled()).isFalse();
  }

  @Test
  void authEnabledWhenTenantsConfigured() {
    var resolver = new TenantResolver(propsWithTenants(List.of(tenant("key-1", "acme"))));
    assertThat(resolver.isAuthEnabled()).isTrue();
  }
}
