package com.trackmywealth.backend.config;

import com.trackmywealth.backend.service.MfaKeyMigrationService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Runs after singleton initialization (including Flyway and JPA initialization) but before the
 * application context finishes refreshing and the embedded HTTP server begins accepting requests. A
 * failed MFA re-encryption therefore prevents startup instead of leaving users unable to log in.
 */
@Component
public class MfaKeyMigrationStartup implements SmartInitializingSingleton {

  private final MfaKeyMigrationService migrationService;

  public MfaKeyMigrationStartup(MfaKeyMigrationService migrationService) {
    this.migrationService = migrationService;
  }

  @Override
  public void afterSingletonsInstantiated() {
    migrationService.migrate();
  }
}
