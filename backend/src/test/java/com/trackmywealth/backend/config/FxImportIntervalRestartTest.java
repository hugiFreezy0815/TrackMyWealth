package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.TrackMyWealthApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-06-07 (#227): an interval an administrator set is still in effect after a restart, whatever
 * {@code FX_IMPORT_CRON} says - otherwise the next redeployment would silently undo it.
 *
 * <p>Starts the application itself, twice, one after the other, rather than as a {@code
 * SpringBootTest}: Quartz registers a scheduler's data source JVM-wide by scheduler name, so a
 * second start next to a running test context would take over and then close its connections. For
 * the same reason both starts use a scheduler name of their own: the cached {@code SpringBootTest}
 * contexts of other classes in this JVM keep the default {@code quartzScheduler} to themselves. The
 * change itself goes through HTTP in {@code AdminFxImportControllerTest}; here it is the stored
 * row, which is all a start reads.
 */
@Testcontainers
class FxImportIntervalRestartTest {

  private static final String ENVIRONMENT_CRON = "0 0 0/12 * * ?";
  // The same for both starts, so the second one replaces the trigger the first one stored.
  private static final String SCHEDULER_NAME = "fx-import-restart-test";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @Test
  void anIntervalSetByAnAdministratorWinsOverTheEnvironmentAtTheNextStart() throws Exception {
    try (ConfigurableApplicationContext first = start()) {
      assertThat(storedScheduledImportCron()).isEqualTo(ENVIRONMENT_CRON);
    }

    execute("UPDATE fx_import_setting SET import_interval_hours = 24");

    // The environment still says every 12 hours; overwrite-existing-jobs makes the start replace
    // the stored trigger with the one it builds, from the stored interval.
    try (ConfigurableApplicationContext restarted = start()) {
      assertThat(storedScheduledImportCron()).isEqualTo("0 0 0 * * ?");
    }
  }

  private static ConfigurableApplicationContext start() {
    // Command-line arguments: they win over application.yml, as the environment does.
    return new SpringApplicationBuilder(TrackMyWealthApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--app.fx.import.enabled=true",
            "--app.fx.import.import-cron=" + ENVIRONMENT_CRON,
            // Triggers are stored but never fire: no provider call during the test.
            "--spring.quartz.auto-startup=false",
            "--spring.quartz.scheduler-name=" + SCHEDULER_NAME);
  }

  private static String storedScheduledImportCron() throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT cron_expression FROM qrtz_cron_triggers"
                    + " WHERE sched_name = '"
                    + SCHEDULER_NAME
                    + "' AND trigger_name = 'scheduled-import'"
                    + " AND trigger_group = '"
                    + FxRateImportJobConfig.JOB_GROUP
                    + "'")) {
      assertThat(row.next()).isTrue();
      return row.getString(1);
    }
  }

  private static void execute(String sql) throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static Connection connect() throws Exception {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }
}
