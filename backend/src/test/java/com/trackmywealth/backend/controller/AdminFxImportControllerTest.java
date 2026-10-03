package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.config.FxRateImportJobConfig;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.FxImportIntervalSource;
import com.trackmywealth.backend.dto.FxImportSettingsResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UpdateFxImportIntervalRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.CronExpression;
import org.quartz.CronTrigger;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * US-06-07 (#227) Definition of Done: an administrator reads and sets the FX import interval, the
 * change reschedules the stored trigger at once, is audited, and is guarded by role, validation and
 * If-Match. That it survives a restart is {@code FxImportIntervalRestartTest}.
 *
 * <p>The import is enabled with its environment cron at every 12 hours, so an administrator's
 * choice is always distinguishable from it. The scheduler is never started: the stored triggers are
 * asserted, no job fires and no provider is called.
 *
 * <p>Expected run times are derived from the instants around the call and the cron itself, never
 * from a fixed number of hours after "now": a DST switch or an hour boundary passing mid-test must
 * not make these tests flaky.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.fx.import.enabled=true",
      "app.fx.import.import-cron=" + AdminFxImportControllerTest.ENVIRONMENT_CRON,
      "spring.quartz.auto-startup=false"
    })
class AdminFxImportControllerTest {

  static final String ENVIRONMENT_CRON = "0 0 0/12 * * ?";
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final String URI = "/api/v1/admin/fx-import";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  @LocalServerPort int port;

  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired Scheduler scheduler;
  @Autowired ObjectMapper objectMapper;

  // The environment's trigger is stored between these two instants in every test.
  private Instant resetStartedAt;
  private Instant resetEndedAt;

  @BeforeEach
  void cleanDatabase() throws Exception {
    for (String sql :
        List.of(
            // Back to "not set" (the trigger keeps its version check: send the stored one).
            "UPDATE fx_import_setting SET import_interval_hours = NULL, updated_by = NULL",
            "DELETE FROM admin_audit_log",
            "DELETE FROM user_session",
            "DELETE FROM refresh_token",
            "DELETE FROM app_user",
            "DELETE FROM workspace_member",
            "DELETE FROM financial_institution",
            "DELETE FROM workspace")) {
      jdbcTemplate.update(sql);
    }
    scheduler.unscheduleJob(FxRateImportJobConfig.SCHEDULED_IMPORT_DEADLINE_TRIGGER_KEY);
    resetStartedAt = Instant.now();
    scheduler.rescheduleJob(
        FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY,
        FxRateImportJobConfig.scheduledImportTrigger(ENVIRONMENT_CRON, BERLIN));
    resetEndedAt = Instant.now();
  }

  @Test
  void untilAnAdministratorSetsOneTheEnvironmentsIntervalIsShown() throws Exception {
    String adminToken = bootstrapAdministrator();

    var result =
        client(adminToken)
            .get()
            .uri(URI)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(FxImportSettingsResponse.class)
            .returnResult();
    FxImportSettingsResponse current =
        CurrentVersion.storedEtag(result, dataSource, "fx_import_setting", settingId());

    assertThat(current.enabled()).isTrue();
    assertThat(current.intervalHours()).isEqualTo(12);
    assertThat(current.intervalSource()).isEqualTo(FxImportIntervalSource.ENVIRONMENT);
    assertThat(current.cron()).isEqualTo(ENVIRONMENT_CRON);
    assertThat(current.cronZone()).isEqualTo("Europe/Berlin");
    assertThat(current.allowedIntervalHours()).containsExactly(1, 2, 6, 12, 24);
    assertThat(current.lastRateDate()).isNull(); // nothing imported yet
    // The environment's cron decides the next run: its first firing once the trigger was stored.
    assertThat(current.nextRunAt().toInstant())
        .isBetween(
            firstFiringAfter(ENVIRONMENT_CRON, resetStartedAt),
            firstFiringAfter(ENVIRONMENT_CRON, resetEndedAt));
    assertThat(current.nextRunAt().toInstant()).isEqualTo(earliestStoredFiring());
  }

  @Test
  void settingTheIntervalReschedulesAtOnceAndIsAudited() throws Exception {
    String adminToken = bootstrapAdministrator();
    UUID adminId = userId("admin@example.com");
    String etag = readEtag(adminToken);

    Instant changeSent = Instant.now();
    var result =
        put(adminToken, 6, etag).expectStatus().isOk().expectBody(FxImportSettingsResponse.class);
    Instant changeAnswered = Instant.now();
    FxImportSettingsResponse changed =
        CurrentVersion.storedEtag(
            result.returnResult(), dataSource, "fx_import_setting", settingId());

    assertThat(changed.intervalHours()).isEqualTo(6);
    assertThat(changed.intervalSource()).isEqualTo(FxImportIntervalSource.ADMINISTRATOR);
    assertThat(changed.cron()).isEqualTo("0 0 0/6 * * ?");
    // The AC: at most 6 hours after the change, also across a DST fall-back (deadline trigger).
    // Quartz counts a cron firing up to a second before the trigger's start as its first.
    assertThat(changed.nextRunAt().toInstant())
        .isAfter(changeSent.minusSeconds(1))
        .isBeforeOrEqualTo(changeAnswered.plus(Duration.ofHours(6)));
    // The response shows the earliest of the stored triggers, the recurring or the deadline one.
    assertThat(changed.nextRunAt().toInstant()).isEqualTo(earliestStoredFiring());

    // The stored trigger every node of the cluster fires on, not just this response.
    CronTrigger stored =
        (CronTrigger) scheduler.getTrigger(FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY);
    assertThat(stored.getCronExpression()).isEqualTo("0 0 0/6 * * ?");
    assertThat(stored.getTimeZone().getID()).isEqualTo("Europe/Berlin");
    assertThat(jdbcTemplate.queryForObject("SELECT updated_by FROM fx_import_setting", UUID.class))
        .isEqualTo(adminId);

    Map<String, Object> audit =
        jdbcTemplate.queryForMap(
            "SELECT actor_user_id, action, target_user_id, details::text"
                + " AS details FROM admin_audit_log");
    assertThat(audit.get("actor_user_id")).isEqualTo(adminId);
    assertThat(audit.get("action")).isEqualTo("FX_IMPORT_INTERVAL_CHANGED");
    assertThat(audit.get("target_user_id")).isNull();
    JsonNode details = objectMapper.readTree((String) audit.get("details"));
    assertThat(details.at("/from/intervalHours").asInt()).isEqualTo(12);
    assertThat(details.at("/from/intervalSource").asString()).isEqualTo("ENVIRONMENT");
    assertThat(details.at("/from/cron").asString()).isEqualTo(ENVIRONMENT_CRON);
    assertThat(details.at("/to/intervalHours").asInt()).isEqualTo(6);
    assertThat(details.at("/to/intervalSource").asString()).isEqualTo("ADMINISTRATOR");
  }

  @Test
  void settingTheSameIntervalAgainChangesNothing() {
    String adminToken = bootstrapAdministrator();
    String etag =
        put(adminToken, 6, readEtag(adminToken))
            .expectStatus()
            .isOk()
            .returnResult()
            .getResponseHeaders()
            .getETag();

    put(adminToken, 6, etag).expectStatus().isOk().expectHeader().valueEquals("ETag", etag);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM admin_audit_log", Long.class))
        .isEqualTo(1L);
  }

  @Test
  void aValueOutsideTheChoicesIsRejectedAndAMissingOneIsInvalid() {
    String adminToken = bootstrapAdministrator();
    String etag = readEtag(adminToken);

    put(adminToken, 5, etag)
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNPROCESSABLE");
    client(adminToken)
        .put()
        .uri(URI)
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{}")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VALIDATION_FAILED");
    assertNothingChanged();
  }

  @Test
  void aChangeNeedsTheCurrentVersion() {
    String adminToken = bootstrapAdministrator();
    String etagReadByBoth = readEtag(adminToken);
    put(adminToken, 6, etagReadByBoth).expectStatus().isOk();

    put(adminToken, 1, etagReadByBoth)
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");
    put(adminToken, 1, null)
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_REQUIRED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT import_interval_hours FROM fx_import_setting", Integer.class))
        .isEqualTo(6);
  }

  @Test
  void onlyAnAdministratorMayReadOrChangeIt() {
    String adminToken = bootstrapAdministrator();
    String etag = readEtag(adminToken);
    createStandardUser(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    client(memberToken).get().uri(URI).exchange().expectStatus().isForbidden();
    put(memberToken, 6, etag).expectStatus().isForbidden();
    anonymousClient().get().uri(URI).exchange().expectStatus().isUnauthorized();
    anonymousClient()
        .put()
        .uri(URI)
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateFxImportIntervalRequest(6))
        .exchange()
        .expectStatus()
        .isUnauthorized();
    assertNothingChanged();
  }

  private void assertNothingChanged() {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT import_interval_hours FROM fx_import_setting", Integer.class))
        .isNull();
    // Creating a user writes its own audit row; only an interval change counts here.
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'FX_IMPORT_INTERVAL_CHANGED'",
                Long.class))
        .isZero();
  }

  // What Quartz stores as a new cron trigger's first firing when it starts at {@code start}: the
  // first one after a second before it, read in the import zone like the trigger.
  private static Instant firstFiringAfter(String cron, Instant start) throws Exception {
    CronExpression expression = new CronExpression(cron);
    expression.setTimeZone(TimeZone.getTimeZone(BERLIN));
    return expression.getNextValidTimeAfter(Date.from(start.minusSeconds(1))).toInstant();
  }

  private Instant earliestStoredFiring() throws Exception {
    return Stream.of(
            FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY,
            FxRateImportJobConfig.SCHEDULED_IMPORT_DEADLINE_TRIGGER_KEY)
        .map(this::storedTrigger)
        .filter(Objects::nonNull)
        .map(trigger -> trigger.getNextFireTime().toInstant())
        .min(Instant::compareTo)
        .orElseThrow();
  }

  private Trigger storedTrigger(TriggerKey key) {
    try {
      return scheduler.getTrigger(key);
    } catch (SchedulerException e) {
      throw new IllegalStateException(e);
    }
  }

  private RestTestClient.ResponseSpec put(String token, int hours, String ifMatch) {
    return client(token)
        .put()
        .uri(URI)
        .headers(
            headers -> {
              if (ifMatch != null) {
                headers.setIfMatch(ifMatch);
              }
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateFxImportIntervalRequest(hours))
        .exchange();
  }

  private String readEtag(String token) {
    return client(token)
        .get()
        .uri(URI)
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult()
        .getResponseHeaders()
        .getETag();
  }

  private UUID settingId() {
    return jdbcTemplate.queryForObject("SELECT id FROM fx_import_setting", UUID.class);
  }

  private UUID userId(String email) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM app_user WHERE email = ?", UUID.class, email);
  }

  private void createStandardUser(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private String login(String email) {
    return anonymousClient()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(email, PASSWORD))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LoginResponse.class)
        .returnResult()
        .getResponseBody()
        .tokens()
        .accessToken();
  }

  private String bootstrapAdministrator() {
    return anonymousClient()
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

  private RestTestClient anonymousClient() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }

  private RestTestClient client(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }
}
