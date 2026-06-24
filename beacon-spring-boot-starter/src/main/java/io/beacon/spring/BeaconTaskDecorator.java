package io.beacon.spring;

import io.beacon.sdk.context.BeaconExecutors;
import org.springframework.core.task.TaskDecorator;

/**
 * Spring {@link TaskDecorator} that wraps every {@link Runnable} submitted to a
 * {@code ThreadPoolTaskExecutor} with
 * {@link BeaconExecutors#wrap(Runnable) BeaconExecutors.wrap(Runnable)}, propagating
 * OpenTelemetry {@code Context} + SLF4J {@code MDC} across the executor boundary
 * (ADR-0008; Pitfall #2 — async context loss across {@code @Async} boundaries).
 *
 * <p>Auto-registered as the named bean {@code beaconTaskDecorator} by
 * {@link BeaconAutoConfiguration}. Spring's {@code ThreadPoolTaskExecutor} does NOT
 * auto-pick {@code TaskDecorator} beans by type, so users must wire it on their
 * executor explicitly:
 *
 * <pre>{@code
 * @Bean
 * ThreadPoolTaskExecutor appExecutor(BeaconTaskDecorator beaconTaskDecorator) {
 *     ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
 *     e.setTaskDecorator(beaconTaskDecorator);
 *     e.initialize();
 *     return e;
 * }
 * }</pre>
 *
 * <p>Users supplying their own {@code TaskDecorator} can compose with this via
 * decorator chaining ({@code r -> beacon.decorate(theirs.decorate(r))}).
 */
public final class BeaconTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return BeaconExecutors.wrap(runnable);
    }
}
