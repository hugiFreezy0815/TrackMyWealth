package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppVersionTest {

  @Test
  void readsMajorMinorPatch() {
    assertThat(AppVersion.parse("1.4.0")).contains(new AppVersion(1, 4, 0));
    assertThat(AppVersion.parse("0.0.0")).contains(new AppVersion(0, 0, 0));
    assertThat(AppVersion.parse("999999.10.2")).contains(new AppVersion(999_999, 10, 2));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "1",
        "1.4",
        "1.4.0.1",
        "v1.4.0",
        "1.4.0-beta.1",
        "1.4.0+42",
        " 1.4.0",
        "01.4.0",
        "1.04.0",
        "1000000.0.0",
        "1.a.0",
        "1..0",
        "-1.0.0"
      })
  void refusesAnythingElse(String value) {
    assertThat(AppVersion.parse(value)).isEmpty();
  }

  @Test
  void aMissingValueIsNoVersion() {
    assertThat(AppVersion.parse(null)).isEmpty();
  }

  @Test
  void comparesNumericallyPartByPart() {
    AppVersion minimum = new AppVersion(1, 10, 0);

    assertThat(new AppVersion(1, 9, 99).isBefore(minimum)).isTrue();
    assertThat(new AppVersion(0, 99, 99).isBefore(minimum)).isTrue();
    assertThat(new AppVersion(1, 10, 0).isBefore(minimum)).isFalse();
    assertThat(new AppVersion(1, 10, 1).isBefore(minimum)).isFalse();
    assertThat(new AppVersion(2, 0, 0).isBefore(minimum)).isFalse();
    assertThat(new AppVersion(1, 2, 3).isBefore(new AppVersion(1, 2, 3))).isFalse();
  }

  @Test
  void printsAsSent() {
    assertThat(new AppVersion(1, 4, 0)).hasToString("1.4.0");
  }
}
