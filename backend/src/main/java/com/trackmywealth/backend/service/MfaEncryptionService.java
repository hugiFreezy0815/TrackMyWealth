package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.MfaProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
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

  /**
   * The text whose Base64 is the public placeholder default of {@code
   * app.security.mfa.encryption-key} in application.yml - compared by decoded bytes rather than
   * kept as a Base64 literal here, so no key-shaped literal lives in source.
   */
  static final String PLACEHOLDER_PHRASE = "CHANGE_ME_IN_ENVIRONMENT_CONFIG1";

  private static final String HOSTED_TOPOLOGY = "hosted";

  private static final Pattern BASE64 =
      Pattern.compile("(?:[A-Za-z0-9+/]{4})+(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?");

  private static final Logger LOG = LoggerFactory.getLogger(MfaEncryptionService.class);

  /** #544: read by the startup migration only, never on the authentication path. */
  private static final SecretKeySpec LEGACY_PLACEHOLDER_KEY =
      new SecretKeySpec(placeholderBytes(), "AES");

  private final SecretKeySpec key;
  private final boolean placeholderKey;
  private final SecureRandom secureRandom = new SecureRandom();

  public MfaEncryptionService(
      MfaProperties properties,
      @Value("${app.deployment.topology:self-hosted}") String deploymentTopology) {
    byte[] keyBytes = decodeKey(properties.encryptionKey());
    if (keyBytes.length != KEY_LENGTH_BYTES) {
      throw new IllegalStateException(
          "app.security.mfa.encryption-key must decode (Base64) to exactly 32 bytes for AES-256");
    }
    this.placeholderKey = MessageDigest.isEqual(keyBytes, placeholderBytes());
    if (placeholderKey) {
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
    this.key = new SecretKeySpec(keyBytes, "AES");
  }

  /**
   * Docker Compose forwards an unset key as an empty string: treated like the documented
   * self-hosted default (the placeholder, which hosted mode still refuses), never as an AES key.
   * Base64 is checked up front, as in {@link AccountIdentifierService}, so the decoder never throws
   * - its message could quote part of the key (e.g. the {@code CHANGE-ME} of .env.example).
   */
  static byte[] decodeKey(String configuredKey) {
    if (configuredKey == null || configuredKey.isBlank()) {
      return placeholderBytes();
    }
    if (!BASE64.matcher(configuredKey).matches()) {
      throw new IllegalStateException(
          "app.security.mfa.encryption-key must be Base64 - set MFA_ENCRYPTION_KEY to the output"
              + " of `openssl rand -base64 32`");
    }
    return Base64.getDecoder().decode(configuredKey);
  }

  private static byte[] placeholderBytes() {
    return PLACEHOLDER_PHRASE.getBytes(StandardCharsets.UTF_8);
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

  /** Whether this deployment still encrypts new secrets with the public development key. */
  boolean usesPlaceholderKey() {
    return placeholderKey;
  }

  /**
   * Only for the startup migration: return a new ciphertext when the stored secret was encrypted
   * with the historical public key. Already-rotated records remain byte-for-byte unchanged.
   *
   * <p>Unlike normal login, migration may read the public legacy key. Unknown/corrupted ciphertext
   * is never reset or ignored: startup fails so the operator can restore the original key or a
   * database backup. No fallback is available on the authentication path.
   */
  Optional<String> reencryptFromPlaceholder(String encoded) {
    if (placeholderKey || tryDecrypt(encoded, key).isPresent()) {
      return Optional.empty();
    }
    String plaintext =
        tryDecrypt(encoded, LEGACY_PLACEHOLDER_KEY)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "An existing MFA secret cannot be decrypted with the configured or legacy"
                            + " key. Restore the previous encryption key or database backup"
                            + " before restarting."));
    return Optional.of(encrypt(plaintext));
  }

  /** The plaintext, or empty when {@code encoded} is corrupt or not encrypted with {@code key}. */
  private static Optional<String> tryDecrypt(String encoded, SecretKeySpec decryptionKey) {
    try {
      return Optional.of(decryptWithKey(encoded, decryptionKey));
    } catch (IllegalStateException wrongKeyOrCorrupt) {
      return Optional.empty();
    }
  }

  /** Reverses {@link #encrypt}; throws if {@code encoded} was not produced by it with this key. */
  public String decrypt(String encoded) {
    return decryptWithKey(encoded, key);
  }

  private static String decryptWithKey(String encoded, SecretKeySpec decryptionKey) {
    final byte[] combined;
    try {
      combined = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Invalid MFA ciphertext encoding", e);
    }
    if (combined.length <= IV_LENGTH_BYTES + TAG_LENGTH_BITS / Byte.SIZE) {
      throw new IllegalStateException("Invalid MFA ciphertext length");
    }
    byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
    byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, decryptionKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to decrypt MFA secret", e);
    }
  }
}
