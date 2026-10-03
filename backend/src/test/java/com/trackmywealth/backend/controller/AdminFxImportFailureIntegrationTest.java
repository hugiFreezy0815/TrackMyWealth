package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.TrackMyWealthApplication;
import com.trackmywealth.backend.config.FxRateImportJobConfig;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.FxImportSettingsResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UpdateFxImportIntervalRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-06-07 (#227): integration coverage for the two operational failure modes that mocks cannot
 * prove: a disabled deployment has no runnable trigger, and a deferred commit failure rolls the
 * setting and the Quartz JDBC trigger back together.
 */
@Testcontainers
class AdminFxImportFailureIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String URI = "/api/v1/admin/fx-import";
  private static final String ENVIRONMENT_CRON = "0 0 0/12 * * ?";
  private static final String DEFERRED_REJECTION_CONSTRAINT = "test_reject_fx_updated_by";
  private static final String DEFERRED_REJECTION_TABLE = "test_fx_updated_by_rejection";
  // Quartz registers a scheduler's data source JVM-wide by scheduler name. A name of its own keeps
  // these starts from taking over, and then closing, the connections of the cached SpringBootTest
  // contexts' "quartzScheduler" in the same JVM.
  private static final String SCHEDULER_NAME = "fx-import-failure-test";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @Test
  void disabledImportIsReportedAndCannotBeChangedThroughHttp() throws Exception {
    try (ConfigurableApplicationContext context = start(false)) {
      cleanDatabase();
      String token = bootstrapAdministrator(context);

      EntityExchangeResult<FxImportSettingsResponse> result =
          client(context, token)
              .get()
              .uri(URI)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(FxImportSettingsResponse.class)
              .returnResult();

      FxImportSettingsResponse current = result.getResponseBody();
      assertThat(current.enabled()).isFalse();
      assertThat(current.nextRunAt()).isNull();
      assertThat(
              context
                  .getBean(Scheduler.class)
                  .getTrigger(FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY))
          .isNull();

      client(context, token)
          .put()
          .uri(URI)
          .header("If-Match", result.getResponseHeaders().getETag())
          .contentType(MediaType.APPLICATION_JSON)
          .body(new UpdateFxImportIntervalRequest(6))
          .exchange()
          .expectStatus()
          .isEqualTo(HttpStatus.CONFLICT)
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("CONFLICT");

      assertThat(queryInteger("SELECT import_interval_hours FROM fx_import_setting")).isNull();
      assertThat(
              queryLong(
                  "SELECT count(*) FROM admin_audit_log"
                      + " WHERE action = 'FX_IMPORT_INTERVAL_CHANGED'"))
          .isZero();
    }
  }

  @Test
  void aDeferredCommitFailureRollsBackTheSettingAndQuartzRescheduleTogether() throws Exception {
    try (ConfigurableApplicationContext context = start(true)) {
      cleanDatabase();
      String token = bootstrapAdministrator(context);
      String etag =
          client(context, token)
              .get()
              .uri(URI)
              .exchange()
              .expectStatus()
              .isOk()
              .returnResult()
              .getResponseHeaders()
              .getETag();
      assertThat(storedScheduledImportCron()).isEqualTo(ENVIRONMENT_CRON);

      // Empty reference table + INITIALLY DEFERRED FK: setting.updated_by is accepted by
      // saveAndFlush(), the audit row and Quartz reschedule both happen, and only transaction
      // commit fails. That makes this a real proof that the JDBC job store shares the transaction.
      execute("CREATE TABLE " + DEFERRED_REJECTION_TABLE + " (id UUID PRIMARY KEY)");
      execute(
          "ALTER TABLE fx_import_setting ADD CONSTRAINT "
              + DEFERRED_REJECTION_CONSTRAINT
              + " FOREIGN KEY (updated_by) REFERENCES "
              + DEFERRED_REJECTION_TABLE
              + " (id) DEFERRABLE INITIALLY DEFERRED");
      try {
        client(context, token)
            .put()
            .uri(URI)
            .header("If-Match", etag)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new UpdateFxImportIntervalRequest(6))
            .exchange()
            // A constraint failing at commit is a DataIntegrityViolationException, which
            // GlobalExceptionHandler answers with its generic 409.
            .expectStatus()
            .isEqualTo(HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("CONFLICT");

        assertThat(queryInteger("SELECT import_interval_hours FROM fx_import_setting")).isNull();
        assertThat(
                queryLong(
                    "SELECT count(*) FROM admin_audit_log"
                        + " WHERE action = 'FX_IMPORT_INTERVAL_CHANGED'"))
            .isZero();
        assertThat(storedScheduledImportCron()).isEqualTo(ENVIRONMENT_CRON);
      } finally {
        execute(
            "ALTER TABLE fx_import_setting DROP CONSTRAINT IF EXISTS "
                + DEFERRED_REJECTION_CONSTRAINT);
        execute("DROP TABLE IF EXISTS " + DEFERRED_REJECTION_TABLE);
      }
    }
  }

  private static ConfigurableApplicationContext start(boolean enabled) {
    return new SpringApplicationBuilder(TrackMyWealthApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--app.rate-limit.enabled=false",
            "--app.fx.import.enabled=" + enabled,
            "--app.fx.import.import-cron=" + ENVIRONMENT_CRON,
            "--spring.quartz.auto-startup=false",
            "--spring.quartz.scheduler-name=" + SCHEDULER_NAME);
  }

  private static void cleanDatabase() throws Exception {
    for (String sql :
        List.of(
            "UPDATE fx_import_setting SET import_interval_hours = NULL, updated_by = NULL",
            "DELETE FROM admin_audit_log",
            "DELETE FROM user_session",
            "DELETE FROM refresh_token",
            "DELETE FROM app_user",
            "DELETE FROM workspace_member",
            "DELETE FROM financial_institution",
            "DELETE FROM workspace")) {
      execute(sql);
    }
  }

  private static String bootstrapAdministrator(ConfigurableApplicationContext context) {
    return anonymousClient(context)
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Workspace A", "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private static RestTestClient client(ConfigurableApplicationContext context, String token) {
    return RestTestClient.bindToServer()
        .baseUrl(baseUrl(context))
        .defaultHeader("Authorization", "Bearer " + token)
        .build();
  }

  private static RestTestClient anonymousClient(ConfigurableApplicationContext context) {
    return RestTestClient.bindToServer().baseUrl(baseUrl(context)).build();
  }

  private static String baseUrl(ConfigurableApplicationContext context) {
    return "http://localhost:"
        + context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
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

  private static Integer queryInteger(String sql) throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery(sql)) {
      assertThat(row.next()).isTrue();
      return row.getObject(1, Integer.class);
    }
  }

  private static long queryLong(String sql) throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery(sql)) {
      assertThat(row.next()).isTrue();
      return row.getLong(1);
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
