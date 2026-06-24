package io.beacon.sdk.severity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SeverityMapperTest {

  @ParameterizedTest
  @CsvSource({"TRACE, 1", "DEBUG, 5", "INFO,  9", "WARN,  13", "ERROR, 17", "FATAL, 21"})
  void numberFor_returns_band_anchor(String band, int anchor) {
    assertThat(SeverityMapper.numberFor(band)).isEqualTo(anchor);
  }

  @ParameterizedTest
  @CsvSource({
    // anchor → text round-trip
    "1, TRACE", "5, DEBUG", "9, INFO", "13, WARN", "17, ERROR", "21, FATAL",
    // off-anchor → collapse to nearest band at or below
    "4, TRACE", "8, DEBUG", "12, INFO", "16, WARN", "20, ERROR", "24, FATAL",
    "18, ERROR", "14, WARN", "10, INFO"
  })
  void textFor_collapses_offanchor_to_band_at_or_below(int number, String expected) {
    assertThat(SeverityMapper.textFor(number)).isEqualTo(expected);
  }

  @Test
  void textFor_rejects_out_of_range() {
    assertThatThrownBy(() -> SeverityMapper.textFor(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SeverityMapper.textFor(25))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void numberFor_rejects_unknown_band_name() {
    assertThatThrownBy(() -> SeverityMapper.numberFor("SEVERE"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
