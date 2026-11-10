package com.trackmywealth.backend.service;

import com.trackmywealth.backend.repository.MfaSecretMigrationRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #544: re-encrypts TOTP secrets still stored under the historical public placeholder key with the
 * configured {@code MFA_ENCRYPTION_KEY}, on startup and before the application accepts requests.
 *
 * <p>One transaction per startup: an advisory lock serializes backends that start together, and
 * every MFA row is locked while it is checked. A secret that neither the configured nor the
 * placeholder key decrypts fails the startup and rolls back every row already re-encrypted, so the
 * operator can restore the previous key or a backup. No plaintext, ciphertext, user id or key is
 * ever logged.
 *
 * <p>A secret already under the configured key is recognized by authenticated AES-GCM decryption
 * and left byte-for-byte unchanged, so a restart changes nothing. There is deliberately no
 * migration from an arbitrary previous key, only from the placeholder.
 */
@Service
public class MfaKeyMigrationService {

  private static final Logger LOG = LoggerFactory.getLogger(MfaKeyMigrationService.class);

  private final MfaSecretMigrationRepository secrets;
  private final TransactionTemplate transactionTemplate;
  private final MfaEncryptionService encryptionService;

  public MfaKeyMigrationService(
      MfaSecretMigrationRepository secrets,
      PlatformTransactionManager transactionManager,
      MfaEncryptionService encryptionService) {
    this.secrets = secrets;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.encryptionService = encryptionService;
  }

  /** Re-encrypts every placeholder-key secret; returns how many it changed. */
  public int migrate() {
    if (encryptionService.usesPlaceholderKey()) {
      return 0;
    }
    int rotated =
        Optional.ofNullable(transactionTemplate.execute(status -> rotateLegacySecrets()))
            .orElseThrow(() -> new IllegalStateException("MFA key migration did not complete."));
    if (rotated > 0) {
      LOG.info("MFA encryption key migration re-encrypted {} legacy secrets.", rotated);
    }
    return rotated;
  }

  private int rotateLegacySecrets() {
    secrets.lockMigration();
    int rotated = 0;
    for (Map.Entry<UUID, String> stored : secrets.lockAllSecrets().entrySet()) {
      Optional<String> replacement = encryptionService.reencryptFromPlaceholder(stored.getValue());
      if (replacement.isPresent()) {
        if (!secrets.replaceSecret(stored.getKey(), stored.getValue(), replacement.get())) {
          throw new IllegalStateException("Could not atomically update a legacy MFA secret.");
        }
        rotated++;
      }
    }
    return rotated;
  }
}
