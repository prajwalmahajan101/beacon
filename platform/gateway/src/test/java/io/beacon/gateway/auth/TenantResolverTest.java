package io.beacon.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.gateway.config.GatewayProperties;
import io.beacon.gateway.config.GatewayProperties.Tenant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantResolverTest {

  @Test
  void resolvesKnownApiKey() {
    GatewayProperties props = propsWithTenant("key-acme", "acme");
    TenantResolver resolver = new TenantResolver(props);

    var result = resolver.resolve("key-acme");

    assertThat(result).isPresent();
    assertThat(result.get().getTenantId()).isEqualTo("acme");
  }

  @Test
  void returnsEmptyForUnknownKey() {
    GatewayProperties props = propsWithTenant("key-acme", "acme");
    TenantResolver resolver = new TenantResolver(props);

    assertThat(resolver.resolve("garbage")).isEmpty();
  }

  @Test
  void returnsEmptyForNull() {
    GatewayProperties props = propsWithTenant("key-acme", "acme");
    TenantResolver resolver = new TenantResolver(props);

    assertThat(resolver.resolve(null)).isEmpty();
  }

  @Test
  void authEnabledWhenTenantsConfigured() {
    GatewayProperties props = propsWithTenant("key-acme", "acme");
    TenantResolver resolver = new TenantResolver(props);

    assertThat(resolver.isAuthEnabled()).isTrue();
  }

  @Test
  void authDisabledWhenNoTenants() {
    GatewayProperties props = new GatewayProperties();
    TenantResolver resolver = new TenantResolver(props);

    assertThat(resolver.isAuthEnabled()).isFalse();
  }

  private static GatewayProperties propsWithTenant(String apiKey, String tenantId) {
    Tenant tenant = new Tenant();
    tenant.setApiKey(apiKey);
    tenant.setTenantId(tenantId);
    GatewayProperties props = new GatewayProperties();
    props.setTenants(List.of(tenant));
    return props;
  }
}
