package io.beacon.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Beacon Spring Boot starter sample (M1.7 / JSDK-08).
 *
 * <p>Boots a Spring Boot 3.x web app that consumes {@code beacon-sdk-spring-adapter} end-to-end.
 * The starter auto-configures {@link io.beacon.sdk.BeaconSdk}, attaches {@link
 * io.beacon.sdk.appender.BeaconLogbackAppender} to the root Logback context (no {@code
 * logback-spring.xml} mutation — Pitfall #18), and exposes {@link
 * io.beacon.spring.BeaconTaskDecorator} as a named bean.
 *
 * <p>{@code @EnableAsync} activates Spring's async proxying so the {@code /async} endpoint's {@code
 * CompletableFuture.supplyAsync(..., taskExecutor)} hop carries MDC + OTel Context across thread
 * boundaries — provided the {@code taskExecutor} has been wired with {@code BeaconTaskDecorator}.
 * See {@link AsyncConfig}.
 */
@SpringBootApplication
@EnableAsync
public class SampleApplication {

  public static void main(String[] args) {
    SpringApplication.run(SampleApplication.class, args);
  }
}
