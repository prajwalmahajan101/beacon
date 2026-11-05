package io.beacon.query.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

  @Test
  void roundTrip() {
    var values = List.of("1717315730000", "abc123");
    String encoded = CursorCodec.encode(values);
    assertThat(CursorCodec.decode(encoded)).isEqualTo(values);
  }

  @Test
  void encodedIsUrlSafeBase64() {
    var values = List.of("1717315730000", "doc-id");
    String encoded = CursorCodec.encode(values);
    assertThat(encoded).doesNotContain("+", "/", "=");
  }

  @Test
  void decodeRejectsTamperedInput() {
    assertThatThrownBy(() -> CursorCodec.decode("not-valid-json!!"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid cursor");
  }

  @Test
  void decodeRejectsEmptyString() {
    assertThatThrownBy(() -> CursorCodec.decode("")).isInstanceOf(IllegalArgumentException.class);
  }
}
