package io.beacon.query;

import io.beacon.query.config.QueryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(QueryProperties.class)
public class BeaconQueryApplication {

  public static void main(String[] args) {
    SpringApplication.run(BeaconQueryApplication.class, args);
  }
}
