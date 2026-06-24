package io.beacon.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Two endpoints that demonstrate the Beacon emit path end-to-end:
 *
 * <ul>
 *   <li>{@code GET /hello} — logs synchronously on the request thread. The
 *       {@code request.id} MDC key flows through the auto-configured
 *       {@link io.beacon.sdk.appender.BeaconLogbackAppender} into Beacon's
 *       Enricher, which stamps it on the emitted record.</li>
 *   <li>{@code GET /async} — hops to {@link AsyncConfig}'s executor via
 *       {@link CompletableFuture#supplyAsync(java.util.function.Supplier, Executor)}.
 *       Because the executor's {@code TaskDecorator} is wired to
 *       {@link io.beacon.spring.BeaconTaskDecorator}, the MDC value
 *       <b>survives</b> the executor hop (Pitfall #2).</li>
 * </ul>
 */
@RestController
public class HelloController {

    private static final Logger log = LoggerFactory.getLogger(HelloController.class);

    private final Executor executor;

    public HelloController(Executor taskExecutor) {
        this.executor = taskExecutor;
    }

    /** Sync: logs on the request thread; MDC + OTel Span (if any) flow through the Enricher. */
    @GetMapping("/hello")
    public String hello() {
        MDC.put("request.id", UUID.randomUUID().toString());
        try {
            log.info("Hello from sync endpoint");
            return "ok";
        } finally {
            MDC.clear();
        }
    }

    /** Async: logs on the executor thread; MDC + OTel Context survive via BeaconTaskDecorator. */
    @GetMapping("/async")
    public CompletableFuture<String> async() {
        MDC.put("request.id", UUID.randomUUID().toString());
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
            log.info("Hello from async executor thread (MDC + OTel Context survive)");
            return "ok-async";
        }, executor);
        MDC.clear();
        return result;
    }
}
