package com.trackmywealth.backend.controller;

import java.io.IOException;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link HardenedRuntimeAuthenticationScenario} on an upgraded installation (US-28-05, #331): the
 * superuser applied every migration before the split, as on a single-role install, so V81 found no
 * runtime role to grant. The split then hands every object to the migration role and grants the
 * runtime role, and the application starts strict on it - the README's upgrade, without compose.
 */
@Testcontainers
class UpgradedRoleSplitAuthenticationHttpTest extends HardenedRuntimeAuthenticationScenario {

  @Container static PostgreSQLContainer<?> postgres = newContainer();

  @BeforeAll
  static void migrateAsTheSuperuserThenSplit() throws IOException, SQLException {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), SUPERUSER, SUPERUSER_PASSWORD)
        .load()
        .migrate();
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
