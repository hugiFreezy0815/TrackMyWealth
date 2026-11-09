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
            () -> new MfaEncryptionService(new MfaProperties(placeholderKey()), "hosted"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("placeholder");
  }

  @Test
  void aSelfHostedInstallStillStartsOnThePlaceholderKeyButItIsWorkable() {
    MfaEncryptionService service =
        new MfaEncryptionService(new MfaProperties(placeholderKey()), SELF_HOSTED);

    assertThat(service.decrypt(service.encrypt("JBSWY3DPEHPK3PXP"))).isEqualTo("JBSWY3DPEHPK3PXP");
  }

  @Test
  void aRealKeyIsAcceptedInTheHostedTopology() {
    MfaEncryptionService service =
        new MfaEncryptionService(new MfaProperties(randomKey()), "hosted");

    assertThat(service.decrypt(service.encrypt("JBSWY3DPEHPK3PXP"))).isEqualTo("JBSWY3DPEHPK3PXP");
  }

  @Test
  void missingKeyIsAllowedOnlyForSelfHostedWithTheDocumentedWarningFallback() {
    MfaEncryptionService unconfigured =
        new MfaEncryptionService(new MfaProperties(""), SELF_HOSTED);
    assertThat(unconfigured.usesPlaceholderKey()).isTrue();
    assertThat(unconfigured.decrypt(unconfigured.encrypt("JBSWY3DPEHPK3PXP")))
        .isEqualTo("JBSWY3DPEHPK3PXP");
    assertThatThrownBy(() -> new MfaEncryptionService(new MfaProperties(""), "hosted"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("placeholder");
  }

  @Test
  void theEnvExampleValueIsRefusedWithoutTheDecoderQuotingTheKey() {
    // #544: .env.example ships CHANGE-ME, which Compose now forwards to the backend.
    for (String notBase64 : new String[] {"CHANGE-ME", "abc", " " + randomKey()}) {
      assertThatThrownBy(() -> new MfaEncryptionService(new MfaProperties(notBase64), SELF_HOSTED))
          .as(notBase64)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be Base64")
          .hasNoCause()
          .message()
          .doesNotContain("CHANGE");
    }
  }

  @Test
  void malformedCiphertextIsRejectedAsADecryptionFailure() {
    MfaEncryptionService service = serviceWithKey(randomKey());
    String tooShort = Base64.getEncoder().encodeToString(new byte[28]);

    for (String corrupt : new String[] {"not-valid-base64", tooShort}) {
      assertThatThrownBy(() -> service.decrypt(corrupt))
          .as(corrupt)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith("Invalid MFA ciphertext");
    }
  }

  @Test
  void reencryptsLegacyCiphertextAndLeavesCurrentCiphertextUnchanged() {
    MfaEncryptionService legacy = serviceWithKey(placeholderKey());
    MfaEncryptionService current = serviceWithKey(randomKey());
    String oldCiphertext = legacy.encrypt("JBSWY3DPEHPK3PXP");
    String alreadyCurrent = current.encrypt("JBSWY3DPEHPK3PXP");

    String rotated = current.reencryptFromPlaceholder(oldCiphertext).orElseThrow();
    assertThat(current.decrypt(rotated)).isEqualTo("JBSWY3DPEHPK3PXP");
    assertThat(current.reencryptFromPlaceholder(rotated)).isEmpty();
    assertThat(current.reencryptFromPlaceholder(alreadyCurrent)).isEmpty();
    assertThat(legacy.reencryptFromPlaceholder(oldCiphertext)).isEmpty();
  }

  @Test
  void rejectsCiphertextFromAnUnknownKeyRatherThanReplacingItsSecret() {
    MfaEncryptionService current = serviceWithKey(randomKey());
    String fromUnknownKey = serviceWithKey(randomKey()).encrypt("JBSWY3DPEHPK3PXP");

    assertThatThrownBy(() -> current.reencryptFromPlaceholder(fromUnknownKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("existing MFA secret");
  }

  @Test
  void thePlaceholderConstantMatchesApplicationYmlSoTheGuardCannotSilentlyDrift() throws Exception {
    String yml =
        new String(
            getClass().getResourceAsStream("/application.yml").readAllBytes(),
            StandardCharsets.UTF_8);

    assertThat(yml).contains(placeholderKey());
  }

  private static MfaEncryptionService serviceWithKey(String base64Key) {
    return new MfaEncryptionService(new MfaProperties(base64Key), SELF_HOSTED);
  }

  private static String placeholderKey() {
    return Base64.getEncoder()
        .encodeToString(MfaEncryptionService.PLACEHOLDER_PHRASE.getBytes(StandardCharsets.UTF_8));
  }

  private static String randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
