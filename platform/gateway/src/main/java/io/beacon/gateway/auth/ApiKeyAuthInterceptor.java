package io.beacon.gateway.auth;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyAuthInterceptor implements ServerInterceptor {

  public static final Context.Key<String> TENANT_ID_KEY = Context.key("beacon-tenant-id");

  public ApiKeyAuthInterceptor(TenantResolver resolver) {}

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    return next.startCall(call, headers);
  }
}
