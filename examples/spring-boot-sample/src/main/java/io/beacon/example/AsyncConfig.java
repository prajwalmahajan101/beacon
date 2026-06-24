package io.beacon.example;

import io.beacon.spring.BeaconTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires the auto-configured {@link BeaconTaskDecorator} onto the application's
 * {@link TaskExecutor}. This is the documented integration point — the starter
 * provides the decorator as a named bean ({@code beaconTaskDecorator}); users
 * opt in by attaching it to their own executor.
 *
 * <p>Without this, MDC + OTel Span context will <b>not</b> survive
 * {@code @Async} / {@code CompletableFuture} hops (Pitfall #2).
 *
 * <p>See ADR-0008 (BeaconExecutors) + ADR-0009 §4 (TaskDecorator opt-in).
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public TaskExecutor taskExecutor(BeaconTaskDecorator beaconTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("sample-async-");
        executor.setTaskDecorator(beaconTaskDecorator);
        executor.initialize();
        return executor;
    }
}
