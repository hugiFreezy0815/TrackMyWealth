package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.MfaProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption for {@code app_user.mfa_totp_secret} at rest (NFR-SEC-001) - the one
 * column-level encryption need US-02-04 introduces, not a general-purpose encryption facility. Each
 * {@link #encrypt} call uses a fresh random IV (GCM requires a unique IV per encryption under the
 * same key), prepended to the returned ciphertext so {@link #decrypt} needs nothing else stored
 * alongside it.
 *
 * <p>The key ships with a public placeholder default (application.yml) so a dev/test environment
 * starts out of the box - the same trade-off {@code jwt.secret} makes. Anyone can decrypt secrets
 * stored under that placeholder, so it is refused outright when {@code app.deployment.topology} is
 * {@code hosted}, and logged loudly otherwise (a self-hosted install that never set the key would
 * otherwise get no signal that its at-rest encryption is decorative).
 */
@Service
public class MfaEncryptionService {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_LENGTH_BYTES = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int KEY_LENGTH_BYTES = 32;

  /** Must match the default of {@code app.security.mfa.encryption-key} in application.yml. */
  static final String PLACEHOLDER_KEY = "Q0hBTkdFX01FX0lOX0VOVklST05NRU5UX0NPTkZJRzE=";

  private static final String HOSTED_TOPOLOGY = "hosted";

  private static final Logger LOG = LoggerFactory.getLogger(MfaEncryptionService.class);

  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  public MfaEncryptionService(
      MfaProperties properties,
      @Value("${app.deployment.topology:self-hosted}") String deploymentTopology) {
    if (PLACEHOLDER_KEY.equals(properties.encryptionKey())) {
      if (HOSTED_TOPOLOGY.equals(deploymentTopology)) {
        throw new IllegalStateException(
            "app.security.mfa.encryption-key is still the public placeholder - set MFA_ENCRYPTION_KEY"
                + " (Base64 of 32 random bytes) before running a hosted deployment");
      }
      LOG.warn(
          "MFA_ENCRYPTION_KEY is not set: TOTP secrets are being encrypted with a publicly known"
              + " placeholder key, so at-rest encryption is ineffective. Set MFA_ENCRYPTION_KEY"
              + " (Base64 of 32 random bytes, e.g. `openssl rand -base64 32`) before real use.");
    }
    byte[] keyBytes = Base64.getDecoder().decode(properties.encryptionKey());
    if (keyBytes.length != KEY_LENGTH_BYTES) {
      throw new IllegalStateException(
          "app.security.mfa.encryption-key must decode (Base64) to exactly 32 bytes for AES-256");
    }
    this.key = new SecretKeySpec(keyBytes, "AES");
  }

  public String encrypt(String plaintext) {
    byte[] iv = new byte[IV_LENGTH_BYTES];
    secureRandom.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder()
          .encodeToString(
              ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to encrypt MFA secret", e);
    }
  }

  /** Reverses {@link #encrypt}; throws if {@code encoded} was not produced by it with this key. */
  public String decrypt(String encoded) {
    byte[] combined = Base64.getDecoder().decode(encoded);
    byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
    byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to decrypt MFA secret", e);
    }
  }
}
