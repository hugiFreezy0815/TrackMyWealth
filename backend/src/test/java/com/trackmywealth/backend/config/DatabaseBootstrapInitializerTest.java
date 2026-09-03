package com.trackmywealth.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises US-01-01's two documented paths ("database does not exist" / "database already
 * exists") directly against a real PostgreSQL instance, per that story's Definition of Done.
 *
 * <p>This deliberately does not go through {@code @SpringBootTest} + {@code @DynamicPropertySource}:
 * {@link DatabaseBootstrapInitializer} is ordered to run before Spring Test's dynamic property
 * customizer binds Testcontainers-provided values to the environment, so a full-context test
 * would bootstrap against the wrong (default, not-yet-running) datasource URL. Driving the
 * initializer directly against a hand-built {@link MockEnvironment} tests its actual contract
 * without depending on that ordering.
 */
@Testcontainers
class DatabaseBootstrapInitializerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
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
        environment.setProperty("spring.datasource.url",
                "jdbc:postgresql://%s:%d/%s".formatted(postgres.getHost(), postgres.getFirstMappedPort(), targetDatabase));
        environment.setProperty("spring.datasource.username", postgres.getUsername());
        environment.setProperty("spring.datasource.password", postgres.getPassword());
        return environment;
    }

    private boolean databaseExists(String databaseName) throws Exception {
        try (Connection connection = maintenanceConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
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
                "jdbc:postgresql://%s:%d/postgres".formatted(postgres.getHost(), postgres.getFirstMappedPort()),
                postgres.getUsername(), postgres.getPassword());
    }
}
