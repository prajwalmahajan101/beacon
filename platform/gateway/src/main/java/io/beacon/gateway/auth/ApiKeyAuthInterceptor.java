package io.beacon.gateway.auth;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyAuthInterceptor implements ServerInterceptor {

  public static final Context.Key<String> TENANT_ID_KEY = Context.key("beacon-tenant-id");

  private static final Metadata.Key<String> AUTH_KEY =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final String BEARER_PREFIX = "Bearer ";

  private final TenantResolver resolver;
  private final TenantRateLimiter rateLimiter;
  private final int defaultRateLimit;

  public ApiKeyAuthInterceptor(
      TenantResolver resolver,
      TenantRateLimiter rateLimiter,
      io.beacon.gateway.config.GatewayProperties properties) {
    this.resolver = resolver;
    this.rateLimiter = rateLimiter;
    this.defaultRateLimit = properties.getDefaultRateLimit();
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (!resolver.isAuthEnabled()) {
      return next.startCall(call, headers);
    }

    String header = headers.get(AUTH_KEY);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      call.close(
          Status.UNAUTHENTICATED.withDescription("missing or malformed Authorization header"),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }

    String apiKey = header.substring(BEARER_PREFIX.length()).trim();
    var tenant = resolver.resolve(apiKey);
    if (tenant.isEmpty()) {
      call.close(Status.UNAUTHENTICATED.withDescription("invalid API key"), new Metadata());
      return new ServerCall.Listener<>() {};
    }

    String tenantId = tenant.get().getTenantId();
    int limit = tenant.get().effectiveRateLimit(defaultRateLimit);
    if (!rateLimiter.tryAcquire(tenantId, limit)) {
      call.close(Status.RESOURCE_EXHAUSTED.withDescription("rate limit exceeded"), new Metadata());
      return new ServerCall.Listener<>() {};
    }

    Context ctx = Context.current().withValue(TENANT_ID_KEY, tenantId);
    return Contexts.interceptCall(ctx, call, headers, next);
  }
}
