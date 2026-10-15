package com.trackmywealth.backend.controller;

import java.io.IOException;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link HardenedRuntimeAuthenticationScenario} on a fresh installation: the role split provisions
 * both roles before Flyway runs, as on a fresh compose volume, so the migration role - no superuser
 * - applies every migration and V81 grants the runtime role itself.
 */
@Testcontainers
class HardenedRuntimeAuthenticationHttpTest extends HardenedRuntimeAuthenticationScenario {

  @Container static PostgreSQLContainer<?> postgres = newContainer();

  @BeforeAll
  static void splitBeforeTheFirstMigration() throws IOException, SQLException {
    splitRoles(postgres);
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registerSplitDatasource(registry, postgres);
  }

  @Override
  String jdbcUrl() {
    return postgres.getJdbcUrl();
  }
}
