package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises US-01-01's two documented paths ("database does not exist" / "database already exists")
 * directly against a real PostgreSQL instance, per that story's Definition of Done.
 *
 * <p>This deliberately does not go through {@code @SpringBootTest} +
 * {@code @DynamicPropertySource}: {@link DatabaseBootstrapInitializer} is ordered to run before
 * Spring Test's dynamic property customizer binds Testcontainers-provided values to the
 * environment, so a full-context test would bootstrap against the wrong (default, not-yet-running)
 * datasource URL. Driving the initializer directly against a hand-built {@link MockEnvironment}
 * tests its actual contract without depending on that ordering.
 */
@Testcontainers
class DatabaseBootstrapInitializerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @Test
  void createsTargetDatabaseWhenItDoesNotExist() throws Exception {
    String targetDatabase = "bootstrap_create_test";

    runInitializer(targetDatabase);

    assertThat(databaseExists(targetDatabase)).isTrue();
  }

  @Test
  void doesNothingWhenTargetDatabaseAlreadyExists() throws Exception {
    String targetDatabase = "bootstrap_existing_test";
    createDatabaseDirectly(targetDatabase);

    runInitializer(targetDatabase); // must not throw attempting a duplicate CREATE DATABASE

    assertThat(databaseExists(targetDatabase)).isTrue();
  }

  @Test
  void doesNothingWhenAutoCreateIsDisabled() throws Exception {
    String targetDatabase = "bootstrap_disabled_test";

    MockEnvironment environment = datasourceEnvironment(targetDatabase);
    environment.setProperty("app.database.auto-create", "false");
    runInitializer(environment);

    assertThat(databaseExists(targetDatabase)).isFalse();
  }

  @Test
  void usesTheMigrationRolesCredentialsWhenTheRolesAreSplit() throws Exception {
    // US-28-05: the runtime role may not create databases; its credentials here would even fail
    // to connect. The migration role's (here the container's superuser) are the ones used.
    String targetDatabase = "bootstrap_migration_role_test";
    MockEnvironment environment = datasourceEnvironment(targetDatabase);
    environment.setProperty("spring.datasource.username", "no_such_runtime_role");
    environment.setProperty("spring.datasource.password", "wrong");
    environment.setProperty("app.database.migration-username", postgres.getUsername());
    environment.setProperty("app.database.migration-password", postgres.getPassword());

    runInitializer(environment);

    assertThat(databaseExists(targetDatabase)).isTrue();
  }

  @Test
  void anEmptyMigrationUsernameKeepsTheDatasourcesCredentials() throws Exception {
    // A single-role compose installation passes DB_MIGRATION_USERNAME as an empty string.
    String targetDatabase = "bootstrap_single_role_test";
    MockEnvironment environment = datasourceEnvironment(targetDatabase);
    environment.setProperty("app.database.migration-username", "");
    environment.setProperty("app.database.migration-password", "");

    runInitializer(environment);

    assertThat(databaseExists(targetDatabase)).isTrue();
  }

  private void runInitializer(String targetDatabase) {
    runInitializer(datasourceEnvironment(targetDatabase));
  }

  private void runInitializer(MockEnvironment environment) {
    ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    when(context.getEnvironment()).thenReturn(environment);

    new DatabaseBootstrapInitializer().initialize(context);
  }

  private MockEnvironment datasourceEnvironment(String targetDatabase) {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty(
        "spring.datasource.url",
        "jdbc:postgresql://%s:%d/%s"
            .formatted(postgres.getHost(), postgres.getFirstMappedPort(), targetDatabase));
    environment.setProperty("spring.datasource.username", postgres.getUsername());
    environment.setProperty("spring.datasource.password", postgres.getPassword());
    return environment;
  }

  private boolean databaseExists(String databaseName) throws Exception {
    try (Connection connection = maintenanceConnection();
        PreparedStatement ps =
            connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
      ps.setString(1, databaseName);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private void createDatabaseDirectly(String databaseName) throws Exception {
    try (Connection connection = maintenanceConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE \"" + databaseName + "\"");
    }
  }

  private Connection maintenanceConnection() throws Exception {
    return DriverManager.getConnection(
        "jdbc:postgresql://%s:%d/postgres"
            .formatted(postgres.getHost(), postgres.getFirstMappedPort()),
        postgres.getUsername(),
        postgres.getPassword());
  }
}
