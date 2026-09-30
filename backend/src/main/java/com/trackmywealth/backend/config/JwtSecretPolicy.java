package com.trackmywealth.backend.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Startup guard for the public JWT signing-key placeholder shipped in {@code application.yml}
 * (FR-AUT-003/005, NFR-SEC-001, #188).
 *
 * <p>The placeholder exists only to keep deliberately insecure development and test environments
 * convenient. A real deployment must fail closed rather than silently sign authentication tokens
 * with a key that is published in this repository. The {@code dev} and {@code test} Spring
 * profiles are the only explicit exceptions.
 */
@Component
public class JwtSecretPolicy {

  static final String PLACEHOLDER_SECRET = "CHANGE_ME_IN_ENVIRONMENT_CONFIG_MIN_32_BYTES";

  private static final String HOSTED_TOPOLOGY = "hosted";
  private static final Profiles INSECURE_PLACEHOLDER_PROFILES = Profiles.of("dev", "test");

  public JwtSecretPolicy(JwtProperties properties, Environment environment) {
    if (!PLACEHOLDER_SECRET.equals(properties.secret())) {
      return;
    }

    boolean explicitlyInsecureProfile =
        environment.acceptsProfiles(INSECURE_PLACEHOLDER_PROFILES);
    boolean hostedTopology =
        HOSTED_TOPOLOGY.equalsIgnoreCase(
            environment.getProperty("app.deployment.topology", "self-hosted"));

    if (hostedTopology || !explicitlyInsecureProfile) {
      throw new IllegalStateException(
          "JWT_SECRET is still the public development placeholder. Set JWT_SECRET to at least 32"
              + " bytes of cryptographically random key material before real use (for example:"
              + " openssl rand -base64 32). The placeholder is allowed only with the dev or test"
              + " Spring profile and never with hosted topology.");
    }
  }
}
