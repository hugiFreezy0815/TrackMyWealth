package com.trackmywealth.backend.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The small connection pool {@code authorization_denial_log} rows are written through (#205).
 *
 * <p>A denial is recorded while the request still holds its connection from the main pool. Taking
 * the audit connection from that same pool ({@code REQUIRES_NEW}) let enough concurrent denials
 * wait on each other until the pool timed out. Taking it from this separate pool cannot: an audit
 * connection is never held while waiting for a main-pool one, so there is no circular wait.
 *
 * <p>It starts from the main pool's own {@code spring.datasource.hikari.*} settings (driver and SSL
 * {@code data-source-properties}, lifetimes, ...), so a deployment configures the database once,
 * and overrides only what makes it the audit pool: its name, size, timeouts and auto-commit. Its
 * connections come on top of {@code DB_POOL_MAX}; size PostgreSQL's {@code max_connections} for
 * both. Hikari's Micrometer metrics are published under {@code pool=denial-audit}.
 *
 * <p>Deliberately not a {@code DataSource} bean: one would make Spring Boot back off its own
 * auto-configured {@code DataSource}, which JPA and Flyway rely on.
 */
@Component
public final class AuthorizationDenialAuditPool implements DisposableBean {

  public static final String POOL_NAME = "denial-audit";

  private static final String MAIN_POOL_PREFIX = "spring.datasource.hikari";

  private final HikariDataSource dataSource;

  public AuthorizationDenialAuditPool(
      DataSourceProperties dataSourceProperties,
      AuthorizationDenialAuditProperties properties,
      Environment environment,
      ObjectProvider<MeterRegistry> meterRegistry) {
    HikariConfig config = new HikariConfig();
    Binder.get(environment).bind(MAIN_POOL_PREFIX, Bindable.ofInstance(config));
    // A denied request keeps its main-pool connection while it waits for an audit connection, so
    // the audit wait must give up first - otherwise one slow audit write holds a main-pool
    // connection for longer than any request would ever wait to get one.
    long mainConnectionTimeoutMillis = config.getConnectionTimeout();
    if (properties.auditConnectionTimeout().toMillis() >= mainConnectionTimeoutMillis) {
      throw new IllegalStateException(
          "app.authorization-denial-audit.audit-connection-timeout must be shorter than "
              + MAIN_POOL_PREFIX
              + ".connection-timeout ("
              + mainConnectionTimeoutMillis
              + " ms)");
    }

    config.setJdbcUrl(dataSourceProperties.determineUrl());
    config.setUsername(dataSourceProperties.determineUsername());
    config.setPassword(dataSourceProperties.determinePassword());
    String driverClassName = dataSourceProperties.determineDriverClassName();
    if (driverClassName != null) {
      config.setDriverClassName(driverClassName);
    }
    config.setPoolName(POOL_NAME);
    config.setMaximumPoolSize(properties.auditPoolSize());
    config.setMinimumIdle(0);
    config.setConnectionTimeout(properties.auditConnectionTimeout().toMillis());
    config.setAutoCommit(true);
    // The statement timeout cancels a slow INSERT; the socket timeout is the backstop for a
    // connection whose cancel request can no longer reach the server (pgjdbc, in seconds).
    config.addDataSourceProperty(
        "socketTimeout", String.valueOf(2 * properties.auditStatementTimeoutSeconds()));
    // Open connections on first use, not at startup: most application contexts never deny.
    config.setInitializationFailTimeout(-1);
    meterRegistry.ifAvailable(
        registry -> config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry)));
    this.dataSource = new HikariDataSource(config);
  }

  public Connection getConnection() throws SQLException {
    return dataSource.getConnection();
  }

  /** A copy of the settings the pool runs with, so tests can check what it inherited. */
  HikariConfig effectiveConfig() {
    HikariConfig copy = new HikariConfig();
    dataSource.copyStateTo(copy);
    return copy;
  }

  @Override
  public void destroy() {
    dataSource.close();
  }
}
