package io.beacon.gateway.transport.grpc;

import io.beacon.gateway.config.GatewayProperties;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the OTLP/gRPC {@link Server} (grpc-netty-shaded) as a Spring {@link SmartLifecycle},
 * separate from Spring's embedded HTTP server. Binds {@code beacon.gateway.grpc-port} on start and
 * shuts down gracefully on stop. A configured port of {@code 0} binds an OS-assigned port (used by
 * tests); {@link #getPort()} exposes the actual bound port.
 */
@Component
public final class GrpcServerLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

  private final int configuredPort;
  private final OtlpGrpcLogsService logsService;

  private Server server;
  private volatile boolean running;

  public GrpcServerLifecycle(GatewayProperties properties, OtlpGrpcLogsService logsService) {
    this.configuredPort = properties.getGrpcPort();
    this.logsService = logsService;
  }

  @Override
  public void start() {
    try {
      server = ServerBuilder.forPort(configuredPort).addService(logsService).build().start();
      running = true;
      log.info("OTLP/gRPC server listening on port {}", server.getPort());
    } catch (IOException e) {
      throw new IllegalStateException(
          "failed to start OTLP/gRPC server on port " + configuredPort, e);
    }
  }

  @Override
  public void stop() {
    if (server != null) {
      server.shutdown();
      try {
        server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      server = null;
    }
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** The actual bound port (resolves a configured {@code 0} to the OS-assigned port). */
  public int getPort() {
    return server != null ? server.getPort() : configuredPort;
  }
}
