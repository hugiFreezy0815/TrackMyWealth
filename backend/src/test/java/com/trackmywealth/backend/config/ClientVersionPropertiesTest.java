package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.trackmywealth.backend.web.AppVersion;
import org.junit.jupiter.api.Test;

class ClientVersionPropertiesTest {

  @Test
  void noMinimumByDefault() {
    assertThat(new ClientVersionProperties(null).minimum()).isEmpty();
    assertThat(new ClientVersionProperties("").minimum()).isEmpty();
    assertThat(new ClientVersionProperties("  ").minimum()).isEmpty();
  }

  @Test
  void readsTheConfiguredMinimum() {
    assertThat(new ClientVersionProperties("1.4.0").minimum()).contains(new AppVersion(1, 4, 0));
    assertThat(new ClientVersionProperties(" 2.0.1 ").minimum()).contains(new AppVersion(2, 0, 1));
  }

  @Test
  void aMalformedMinimumFailsTheStartInsteadOfSwitchingTheCheckOff() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ClientVersionProperties("1.4"))
        .withMessageContaining("MINIMUM_APP_VERSION")
        .withMessageContaining("\"1.4\"");
  }
}
