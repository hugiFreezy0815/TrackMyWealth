package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * #189: the token issuer and signing secret are mandatory. JJWT's {@code requireIssuer} checks
 * nothing when given no issuer, so a blank one would switch the issuer check off; instead, a
 * deployment without them does not start.
 */
class JwtPropertiesTest {

  private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Binding.class);

  @Test
  void aBlankIssuerOrSecretIsRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> new JwtProperties(null, 15, 30, SECRET));
    assertThatIllegalArgumentException().isThrownBy(() -> new JwtProperties(" ", 15, 30, SECRET));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new JwtProperties("trackmywealth", 15, 30, null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new JwtProperties("trackmywealth", 15, 30, ""));
  }

  @Test
  void theApplicationDoesNotStartWithoutAnIssuer() {
    runner
        .withPropertyValues("app.security.jwt.secret=" + SECRET)
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues("app.security.jwt.issuer=", "app.security.jwt.secret=" + SECRET)
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void aConfiguredIssuerAndSecretBind() {
    runner
        .withPropertyValues(
            "app.security.jwt.issuer=trackmywealth", "app.security.jwt.secret=" + SECRET)
        .run(
            context ->
                assertThat(context.getBean(JwtProperties.class).issuer())
                    .isEqualTo("trackmywealth"));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(JwtProperties.class)
  static class Binding {}
}
