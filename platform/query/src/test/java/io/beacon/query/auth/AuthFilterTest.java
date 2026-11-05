package io.beacon.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.query.config.QueryProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthFilterTest {

  private static QueryProperties.Tenant tenant(String apiKey, String tenantId) {
    var t = new QueryProperties.Tenant();
    t.setApiKey(apiKey);
    t.setTenantId(tenantId);
    return t;
  }

  private static TenantResolver resolverWith(List<QueryProperties.Tenant> tenants) {
    var props = new QueryProperties();
    props.setTenants(tenants);
    return new TenantResolver(props);
  }

  @Test
  void openModePassesThrough() throws Exception {
    var filter = new ApiKeyAuthFilter(resolverWith(List.of()));
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilterInternal(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(request.getAttribute(ApiKeyAuthFilter.TENANT_ID_ATTR)).isNull();
  }

  @Test
  void missingHeaderReturns401() throws Exception {
    var filter = new ApiKeyAuthFilter(resolverWith(List.of(tenant("key-1", "acme"))));
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilterInternal(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void badKeyReturns401() throws Exception {
    var filter = new ApiKeyAuthFilter(resolverWith(List.of(tenant("key-1", "acme"))));
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer wrong-key");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilterInternal(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void validKeySetsTenantAttribute() throws Exception {
    var filter = new ApiKeyAuthFilter(resolverWith(List.of(tenant("key-1", "acme"))));
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer key-1");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilterInternal(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(request.getAttribute(ApiKeyAuthFilter.TENANT_ID_ATTR)).isEqualTo("acme");
  }

  @Test
  void nonBearerPrefixReturns401() throws Exception {
    var filter = new ApiKeyAuthFilter(resolverWith(List.of(tenant("key-1", "acme"))));
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    filter.doFilterInternal(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
  }
}
