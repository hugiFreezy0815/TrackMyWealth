package com.trackmywealth.backend.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Ensures the target PostgreSQL database exists before Spring Boot's own {@code DataSource}
 * auto-configuration (and, immediately after it, Flyway) tries to connect to it.
 *
 * <p><b>Why this exists.</b> Flyway can create every table in an empty database, but it cannot
 * create the database itself - by the time Flyway runs, the application's primary {@code
 * DataSource} bean has already opened (or failed to open) a connection to {@code
 * app.datasource.url}. On a freshly installed PostgreSQL server - the expected state for a
 * first-time self-hosted deployment (PR-002) - that connection attempt fails with {@code FATAL:
 * database "trackmywealth" does not exist} before Spring Boot gets anywhere near Flyway.
 *
 * <p>This class runs as an {@link ApplicationContextInitializer}, which executes during context
 * preparation, strictly before any {@code @Bean} (including the datasource) is instantiated. It:
 *
 * <ol>
 *   <li>parses {@code spring.datasource.url} to recover host, port and target database name;
 *   <li>opens a short-lived JDBC connection to the PostgreSQL <em>maintenance</em> database ({@code
 *       app.database.maintenance-database}, default {@code postgres}) using the same credentials;
 *   <li>issues {@code CREATE DATABASE <name>} only if a row for it is not already present in {@code
 *       pg_database};
 *   <li>closes the connection and lets the normal Spring Boot startup sequence continue.
 * </ol>
 *
 * <p>Set {@code app.database.auto-create=false} (env {@code DB_AUTO_CREATE=false}) to disable this
 * behaviour, e.g. in a hosted deployment where database provisioning is handled by
 * infrastructure-as-code and the application role deliberately has no CREATEDB privilege.
 *
 * <p>Registered via {@code
 * META-INF/spring/org.springframework.boot.ApplicationContextInitializer.imports} (the Spring Boot
 * 3 replacement for the {@code spring.factories} mechanism).
 */
public class DatabaseBootstrapInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

  // SLF4J works via a static factory backed by whatever's on the classpath (logback here, from
  // spring-boot-starter) - unlike a Spring-managed bean, it needs no DI container, so it's safe
  // to use even this early in context preparation, before any @Bean exists.
  private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrapInitializer.class);

  private static final Pattern JDBC_URL_PATTERN =
      Pattern.compile(
          "jdbc:postgresql://(?<host>[^:/]+)(:(?<port>\\d+))?/(?<database>[^?;]+)(?<params>[?;].*)?");

  @Override
  public int getOrder() {
    // Must run before DataSourceAutoConfiguration and FlywayAutoConfiguration.
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    ConfigurableEnvironment env = applicationContext.getEnvironment();

    boolean autoCreate = env.getProperty("app.database.auto-create", Boolean.class, true);
    if (!autoCreate) {
      return;
    }

    String url = env.getProperty("spring.datasource.url");
    String username = env.getProperty("spring.datasource.username");
    String password = env.getProperty("spring.datasource.password");
    String maintenanceDb = env.getProperty("app.database.maintenance-database", "postgres");

    if (url == null || username == null) {
      // No datasource configured yet (e.g. running under a test profile that
      // supplies its own DataSource, such as Testcontainers) - nothing to bootstrap.
      return;
    }

    Matcher matcher = JDBC_URL_PATTERN.matcher(url);
    if (!matcher.matches()) {
      // Non-standard URL (e.g. a JNDI lookup, or a cloud-managed connection string with a
      // driver-specific scheme). Bootstrapping is a convenience for the common case only;
      // skip it silently and let the operator provision the database out of band.
      return;
    }

    String host = matcher.group("host");
    String port = matcher.group("port") != null ? matcher.group("port") : "5432";
    String targetDatabase = matcher.group("database");
    String maintenanceUrl = "jdbc:postgresql://" + host + ":" + port + "/" + maintenanceDb;

    try (Connection connection = DriverManager.getConnection(maintenanceUrl, username, password)) {
      if (!databaseExists(connection, targetDatabase)) {
        createDatabase(connection, targetDatabase);
      }
    } catch (Exception ex) {
      // Do not fail startup here: a hosted deployment may intentionally run the
      // application role without CREATEDB, in which case the database is expected to
      // already exist and the subsequent DataSource connection attempt will surface a
      // clear, standard Spring Boot startup failure if it genuinely does not.
      log.warn(
          "Could not verify/create database '{}' via maintenance database '{}'. Continuing"
              + " startup; if the target database does not already exist, the application will"
              + " fail to connect in the next startup phase.",
          targetDatabase,
          maintenanceDb,
          ex);
    }
  }

  private boolean databaseExists(Connection connection, String databaseName) throws Exception {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
      ps.setString(1, databaseName);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private void createDatabase(Connection connection, String databaseName) throws Exception {
    // Identifier cannot be parameterised in DDL; the value comes from our own trusted
    // configuration (spring.datasource.url), not from user input.
    String safeName = databaseName.replace("\"", "");
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE \"" + safeName + "\" ENCODING 'UTF8'");
    }
  }
}
