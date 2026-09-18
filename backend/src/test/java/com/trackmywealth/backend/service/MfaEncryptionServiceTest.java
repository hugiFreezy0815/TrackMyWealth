package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.config.MfaProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * US-02-04 / NFR-SEC-001: the at-rest encryption of {@code app_user.mfa_totp_secret}, including the
 * guard against running with the public placeholder key.
 */
class MfaEncryptionServiceTest {

  private static final String SELF_HOSTED = "self-hosted";

  @Test
  void encryptedSecretsRoundTripAndNeverContainThePlaintext() {
    MfaEncryptionService service = serviceWithKey(randomKey());

    String encrypted = service.encrypt("JBSWY3DPEHPK3PXP");

    assertThat(encrypted).doesNotContain("JBSWY3DPEHPK3PXP");
    assertThat(service.decrypt(encrypted)).isEqualTo("JBSWY3DPEHPK3PXP");
  }

  @Test
  void everyEncryptionUsesAFreshIvSoTheSamePlaintextNeverEncryptsTheSameTwice() {
    MfaEncryptionService service = serviceWithKey(randomKey());

    assertThat(service.encrypt("JBSWY3DPEHPK3PXP"))
        .isNotEqualTo(service.encrypt("JBSWY3DPEHPK3PXP"));
  }

  @Test
  void aTamperedCiphertextIsRejectedRatherThanDecryptedToGarbage() {
    MfaEncryptionService service = serviceWithKey(randomKey());
    byte[] bytes = Base64.getDecoder().decode(service.encrypt("JBSWY3DPEHPK3PXP"));
    bytes[bytes.length - 1] ^= 0x01;
    String tampered = Base64.getEncoder().encodeToString(bytes);

    assertThatThrownBy(() -> service.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aSecretEncryptedUnderOneKeyCannotBeDecryptedUnderAnother() {
    String encrypted = serviceWithKey(randomKey()).encrypt("JBSWY3DPEHPK3PXP");

    assertThatThrownBy(() -> serviceWithKey(randomKey()).decrypt(encrypted))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aKeyThatIsNotExactly32BytesFailsStartup() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> serviceWithKey(shortKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exactly 32 bytes");
  }

  @Test
  void theHostedTopologyRefusesThePublicPlaceholderKey() {
    assertThatThrownBy(
            () ->
                new MfaEncryptionService(
                    new MfaProperties(MfaEncryptionService.PLACEHOLDER_KEY), "hosted"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("placeholder");
  }

  @Test
  void aSelfHostedInstallStillStartsOnThePlaceholderKeyButItIsWorkable() {
    MfaEncryptionService service =
        new MfaEncryptionService(
            new MfaProperties(MfaEncryptionService.PLACEHOLDER_KEY), SELF_HOSTED);

    assertThat(service.decrypt(service.encrypt("JBSWY3DPEHPK3PXP"))).isEqualTo("JBSWY3DPEHPK3PXP");
  }

  @Test
  void aRealKeyIsAcceptedInTheHostedTopology() {
    MfaEncryptionService service =
        new MfaEncryptionService(new MfaProperties(randomKey()), "hosted");

    assertThat(service.decrypt(service.encrypt("JBSWY3DPEHPK3PXP"))).isEqualTo("JBSWY3DPEHPK3PXP");
  }

  @Test
  void thePlaceholderConstantMatchesApplicationYmlSoTheGuardCannotSilentlyDrift() throws Exception {
    String yml =
        new String(
            getClass().getResourceAsStream("/application.yml").readAllBytes(),
            StandardCharsets.UTF_8);

    assertThat(yml).contains(MfaEncryptionService.PLACEHOLDER_KEY);
  }

  private static MfaEncryptionService serviceWithKey(String base64Key) {
    return new MfaEncryptionService(new MfaProperties(base64Key), SELF_HOSTED);
  }

  private static String randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
