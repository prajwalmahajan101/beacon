package io.beacon.gateway.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

  public static final String TENANT_ID_ATTR = "beacon.tenantId";
  private static final String BEARER_PREFIX = "Bearer ";

  private final TenantResolver resolver;
  private final TenantRateLimiter rateLimiter;
  private final int defaultRateLimit;

  public ApiKeyAuthFilter(
      TenantResolver resolver,
      TenantRateLimiter rateLimiter,
      io.beacon.gateway.config.GatewayProperties properties) {
    this.resolver = resolver;
    this.rateLimiter = rateLimiter;
    this.defaultRateLimit = properties.getDefaultRateLimit();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!resolver.isAuthEnabled()) {
      filterChain.doFilter(request, response);
      return;
    }

    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }

    String apiKey = header.substring(BEARER_PREFIX.length()).trim();
    var tenant = resolver.resolve(apiKey);
    if (tenant.isEmpty()) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }

    String tenantId = tenant.get().getTenantId();
    int limit = tenant.get().effectiveRateLimit(defaultRateLimit);
    if (!rateLimiter.tryAcquire(tenantId, limit)) {
      response.setIntHeader("Retry-After", (int) rateLimiter.retryAfterSeconds(tenantId, limit));
      response.sendError(429);
      return;
    }

    request.setAttribute(TENANT_ID_ATTR, tenantId);
    filterChain.doFilter(request, response);
  }
}
