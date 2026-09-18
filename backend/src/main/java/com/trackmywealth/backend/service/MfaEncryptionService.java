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
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption for {@code app_user.mfa_totp_secret} at rest (NFR-SEC-001) - the one
 * column-level encryption need US-02-04 introduces, not a general-purpose encryption facility. Each
 * {@link #encrypt} call uses a fresh random IV (GCM requires a unique IV per encryption under the
 * same key), prepended to the returned ciphertext so {@link #decrypt} needs nothing else stored
 * alongside it.
 */
@Service
public class MfaEncryptionService {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_LENGTH_BYTES = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int KEY_LENGTH_BYTES = 32;

  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  public MfaEncryptionService(MfaProperties properties) {
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
