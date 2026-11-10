package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.service.MfaKeyMigrationService;
import org.junit.jupiter.api.Test;

/**
 * #544: the migration runs once all singletons exist, and its failure is not swallowed - it ends
 * the context refresh before the web server and the scheduler start.
 */
class MfaKeyMigrationStartupTest {

  private final MfaKeyMigrationService migration = mock(MfaKeyMigrationService.class);
  private final MfaKeyMigrationStartup startup = new MfaKeyMigrationStartup(migration);

  @Test
  void runsTheMigration() {
    startup.afterSingletonsInstantiated();

    verify(migration).migrate();
  }

  @Test
  void aFailedMigrationFailsTheStartup() {
    IllegalStateException failure = new IllegalStateException("undecryptable");
    when(migration.migrate()).thenThrow(failure);

    assertThatThrownBy(startup::afterSingletonsInstantiated).isSameAs(failure);
  }
}
