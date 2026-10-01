package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.mock.env.MockEnvironment;

/**
 * #205: the audit pool inherits the main pool's database settings but keeps its own size and
 * timeouts, and refuses a connection timeout that isn't shorter than the main pool's. Building it
 * opens no connection, so no database is needed.
 */
class AuthorizationDenialAuditPoolTest {

  private static final ObjectProvider<MeterRegistry> NO_METRICS =
      new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);

  @Test
  void inheritsTheMainPoolsDatabaseSettingsButKeepsItsOwnSizeAndTimeouts() throws Exception {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.datasource.hikari.connection-timeout", "5000")
            .withProperty("spring.datasource.hikari.maximum-pool-size", "10")
            .withProperty("spring.datasource.hikari.max-lifetime", "600000")
            .withProperty("spring.datasource.hikari.data-source-properties.sslmode", "require");

    AuthorizationDenialAuditPool pool =
        new AuthorizationDenialAuditPool(
            dataSourceProperties(), properties(Duration.ofSeconds(1)), environment, NO_METRICS);
    try {
      HikariConfig config = pool.effectiveConfig();

      assertThat(config.getJdbcUrl()).isEqualTo("jdbc:postgresql://db:5432/trackmywealth");
      assertThat(config.getUsername()).isEqualTo("app");
      assertThat(config.getMaxLifetime()).as("inherited").isEqualTo(600_000L);
      assertThat(config.getDataSourceProperties())
          .as("inherited driver properties, plus the socket-timeout backstop")
          .containsEntry("sslmode", "require")
          .containsEntry("socketTimeout", "4");
      assertThat(config.getPoolName()).isEqualTo(AuthorizationDenialAuditPool.POOL_NAME);
      assertThat(config.getMaximumPoolSize()).as("its own size").isEqualTo(2);
      assertThat(config.getMinimumIdle()).isZero();
      assertThat(config.getConnectionTimeout()).as("its own timeout").isEqualTo(1000L);
      assertThat(config.isAutoCommit()).isTrue();
    } finally {
      pool.destroy();
    }
  }

  @Test
  void anAuditConnectionTimeoutNotShorterThanTheMainPoolsIsRejected() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.datasource.hikari.connection-timeout", "1000");

    assertThatThrownBy(
            () ->
                new AuthorizationDenialAuditPool(
                    dataSourceProperties(),
                    properties(Duration.ofSeconds(1)),
                    environment,
                    NO_METRICS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("audit-connection-timeout must be shorter than");
  }

  private static DataSourceProperties dataSourceProperties() throws Exception {
    DataSourceProperties properties = new DataSourceProperties();
    properties.setUrl("jdbc:postgresql://db:5432/trackmywealth");
    properties.setUsername("app");
    properties.setPassword("secret");
    properties.afterPropertiesSet();
    return properties;
  }

  private static AuthorizationDenialAuditProperties properties(Duration connectionTimeout) {
    return new AuthorizationDenialAuditProperties(
        200,
        Duration.ofHours(1),
        100,
        2,
        connectionTimeout,
        Duration.ofSeconds(2),
        Duration.ofDays(90),
        Duration.ofHours(1),
        5000);
  }
}
