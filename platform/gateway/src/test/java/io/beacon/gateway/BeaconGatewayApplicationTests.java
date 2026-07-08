package io.beacon.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Context-load smoke test (T2): the Spring context wires with the bundled {@code application.yaml}
 * without a running broker (the Kafka producer connects lazily on first send, not at startup) and
 * {@link GatewayProperties} binds its defaults.
 */
@SpringBootTest
class BeaconGatewayApplicationTests {

  @Autowired private GatewayProperties properties;

  @Test
  void contextLoadsAndBindsGatewayProperties() {
    assertThat(properties).isNotNull();
    assertThat(properties.getGrpcPort()).isEqualTo(4317);
    assertThat(properties.getKafka().getTopic()).isEqualTo("beacon.logs");
    assertThat(properties.getKafka().getProduceTimeoutMs()).isEqualTo(5_000L);
  }
}
