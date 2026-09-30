package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;

class JwtSecretPolicyTest {

  private static final String ISSUER = "trackmywealth";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(TestConfiguration.class)
          .withPropertyValues(
              "app.security.jwt.issuer=" + ISSUER,
              "app.security.jwt.access-token-ttl-minutes=15",
              "app.security.jwt.refresh-token-ttl-days=30");

  @Test
  void springContextFailsWithPlaceholderWhenNoInsecureProfileIsActive() {
    contextRunner
        .withPropertyValues("app.security.jwt.secret=" + JwtSecretPolicy.PLACEHOLDER_SECRET)
        .run(
            context ->
                org.assertj.core.api.Assertions.assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("JWT_SECRET"));
  }

  @Test
  void springContextStartsWithPlaceholderUnderExplicitTestProfile() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=test",
            "app.security.jwt.secret=" + JwtSecretPolicy.PLACEHOLDER_SECRET)
        .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasNotFailed());
  }

  @Test
  void springContextStartsWithRandomSecretWithoutDevelopmentProfile() {
    contextRunner
        .withPropertyValues("app.security.jwt.secret=" + randomSecret())
        .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasNotFailed());
  }

  @Test
  void publicPlaceholderIsAllowedOnlyInDevProfile() {
    JwtProperties properties = properties(JwtSecretPolicy.PLACEHOLDER_SECRET);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");

    assertThatCode(() -> new JwtSecretPolicy(properties, environment)).doesNotThrowAnyException();
  }

  @Test
  void publicPlaceholderIsAllowedOnlyInTestProfile() {
    JwtProperties properties = properties(JwtSecretPolicy.PLACEHOLDER_SECRET);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");

    assertThatCode(() -> new JwtSecretPolicy(properties, environment)).doesNotThrowAnyException();
  }

  @Test
  void publicPlaceholderIsRejectedWhenNoDevelopmentProfileIsActive() {
    JwtProperties properties = properties(JwtSecretPolicy.PLACEHOLDER_SECRET);

    assertThatThrownBy(() -> new JwtSecretPolicy(properties, new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET")
        .hasMessageContaining("public development placeholder")
        .hasMessageNotContaining(JwtSecretPolicy.PLACEHOLDER_SECRET);
  }

  @Test
  void publicPlaceholderIsRejectedForHostedDeploymentToo() {
    JwtProperties properties = properties(JwtSecretPolicy.PLACEHOLDER_SECRET);
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("app.deployment.topology", "hosted");

    assertThatThrownBy(() -> new JwtSecretPolicy(properties, environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET");
  }

  @Test
  void secretShorterThan256BitsIsRejectedBeforePolicyEvaluation() {
    assertThatThrownBy(() -> properties("too-short"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 32 bytes");
  }

  @Test
  void repeatedCharacterSecretIsRejectedAsTriviallyLowEntropy() {
    assertThatThrownBy(() -> properties("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("low-entropy");
  }

  @Test
  void cryptographicallyRandomSecretIsAcceptedWithoutDevelopmentProfile() {
    JwtProperties properties = properties(randomSecret());

    assertThatCode(() -> new JwtSecretPolicy(properties, new MockEnvironment()))
        .doesNotThrowAnyException();
  }

  private static JwtProperties properties(String secret) {
    return new JwtProperties(ISSUER, 15, 30, secret);
  }

  private static String randomSecret() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  @EnableConfigurationProperties(JwtProperties.class)
  @Import(JwtSecretPolicy.class)
  static class TestConfiguration {}
}
