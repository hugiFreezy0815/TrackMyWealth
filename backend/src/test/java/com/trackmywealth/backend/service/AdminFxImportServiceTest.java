package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.config.FxRateImportJobConfig;
import com.trackmywealth.backend.config.FxRateImportProperties;
import com.trackmywealth.backend.dto.FxImportSettingsResponse;
import com.trackmywealth.backend.dto.UpdateFxImportIntervalRequest;
import com.trackmywealth.backend.repository.AdminAuditLogRepository;
import com.trackmywealth.backend.repository.FxImportSettingRepository;
import com.trackmywealth.backend.repository.FxRateRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-06-07 (#227): focused service-level branches around scheduler recovery/failure and the DST
 * deadline. Disabled deployment behavior and transaction rollback are also covered against the real
 * HTTP/PostgreSQL/Quartz stack by {@code AdminFxImportFailureIntegrationTest}.
 */
class AdminFxImportServiceTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final UUID ADMIN = UUID.randomUUID();
  private static final Clock ORDINARY_DAY =
      Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);

  private final FxImportSettingRepository settingRepository = mock(FxImportSettingRepository.class);
  private final FxRateImportService importService = mock(FxRateImportService.class);
  private final FxRateRepository fxRateRepository = mock(FxRateRepository.class);
  private final Scheduler scheduler = mock(Scheduler.class);
  private final AdminAuditLogRepository auditRepository = mock(AdminAuditLogRepository.class);

  @BeforeEach
  void setUp() {
    when(settingRepository.findTheSetting())
        .thenReturn(Optional.of(FxImportScheduleServiceTest.setting(null, 0)));
    when(settingRepository.saveAndFlush(any()))
        .thenReturn(FxImportScheduleServiceTest.setting(6, 1));
    when(importService.source()).thenReturn("ECB");
    when(fxRateRepository.findLatestRateDate("ECB")).thenReturn(Optional.empty());
  }

  @Test
  void withoutAStoredTriggerTheImportIsScheduledAnewRatherThanTheChangeBeingLost()
      throws Exception {
    Date next = Date.from(Instant.parse("2026-10-07T04:00:00Z"));
    when(scheduler.rescheduleJob(eq(FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY), any()))
        .thenReturn(null);
    when(scheduler.scheduleJob(any(Trigger.class))).thenReturn(next);
    Trigger stored = mock(Trigger.class);
    when(stored.getNextFireTime()).thenReturn(next);
    when(scheduler.getTrigger(FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY))
        .thenReturn(stored);

    FxImportSettingsResponse response =
        service(true).updateInterval(new UpdateFxImportIntervalRequest(6), 0, ADMIN);

    verify(scheduler).scheduleJob(any(Trigger.class));
    assertThat(response.nextRunAt().toInstant()).isEqualTo(next.toInstant());
    assertThat(response.nextRunAt().getOffset())
        .isEqualTo(BERLIN.getRules().getOffset(next.toInstant()));
  }

  @Test
  void onAnOrdinaryDayTheRecurringTriggerAloneMeetsTheIntervalAndNoDeadlineIsAdded()
      throws Exception {
    // 02:00 CEST, changed to every 6 hours: the next clock-aligned run, 06:00, is 4 hours away.
    Date next = Date.from(Instant.parse("2026-10-07T04:00:00Z"));
    when(scheduler.rescheduleJob(eq(FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY), any()))
        .thenReturn(next);
    Trigger stored = mock(Trigger.class);
    when(stored.getNextFireTime()).thenReturn(next);
    when(scheduler.getTrigger(FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY))
        .thenReturn(stored);

    FxImportSettingsResponse response =
        service(true).updateInterval(new UpdateFxImportIntervalRequest(6), 0, ADMIN);

    // A deadline left from an earlier change is dropped, and none is added.
    verify(scheduler).unscheduleJob(FxRateImportJobConfig.SCHEDULED_IMPORT_DEADLINE_TRIGGER_KEY);
    verify(scheduler, never()).scheduleJob(any(Trigger.class));
    assertThat(response.nextRunAt().toInstant()).isEqualTo(next.toInstant());
  }

  @Test
  void aDstFallbackCannotPushTheFirstRunPastTheSelectedInterval() throws Exception {
    Instant changedAt = Instant.parse("2026-10-24T22:01:00Z"); // 00:01 CEST, fall-back day.
    Instant deadline = changedAt.plus(Duration.ofHours(6));
    Date recurringNext = Date.from(Instant.parse("2026-10-25T05:00:00Z")); // 06:00 CET: +6h59.

    when(scheduler.rescheduleJob(eq(FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY), any()))
        .thenReturn(recurringNext);
    Trigger recurring = mock(Trigger.class);
    when(recurring.getNextFireTime()).thenReturn(recurringNext);
    Trigger deadlineTrigger = mock(Trigger.class);
    when(deadlineTrigger.getNextFireTime()).thenReturn(Date.from(deadline));
    when(scheduler.getTrigger(FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY))
        .thenReturn(recurring);
    when(scheduler.getTrigger(FxRateImportJobConfig.SCHEDULED_IMPORT_DEADLINE_TRIGGER_KEY))
        .thenReturn(deadlineTrigger);

    FxImportSettingsResponse response =
        service(true, Clock.fixed(changedAt, ZoneOffset.UTC))
            .updateInterval(new UpdateFxImportIntervalRequest(6), 0, ADMIN);

    verify(scheduler)
        .scheduleJob(
            argThat(
                trigger ->
                    trigger
                            .getKey()
                            .equals(FxRateImportJobConfig.SCHEDULED_IMPORT_DEADLINE_TRIGGER_KEY)
                        && trigger.getStartTime().toInstant().equals(deadline)));
    assertThat(response.nextRunAt().toInstant()).isEqualTo(deadline);
    assertThat(Duration.between(changedAt, response.nextRunAt().toInstant()))
        .isEqualTo(Duration.ofHours(6));
  }

  @Test
  void aSchedulerFailureFailsTheChangeSoItsTransactionRollsBack() throws Exception {
    when(scheduler.rescheduleJob(any(), any())).thenThrow(new SchedulerException("store down"));

    assertThatThrownBy(
            () -> service(true).updateInterval(new UpdateFxImportIntervalRequest(6), 0, ADMIN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reschedule");
  }

  @Test
  void whileTheImportIsDisabledNothingIsScheduledAndAChangeIsRejected() {
    AdminFxImportService service = service(false);

    FxImportSettingsResponse current = service.current();
    assertThat(current.enabled()).isFalse();
    assertThat(current.nextRunAt()).isNull();

    assertThatThrownBy(() -> service.updateInterval(new UpdateFxImportIntervalRequest(6), 0, ADMIN))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    verifyNoInteractions(scheduler, auditRepository);
  }

  private AdminFxImportService service(boolean enabled) {
    return service(enabled, ORDINARY_DAY);
  }

  private AdminFxImportService service(boolean enabled, Clock clock) {
    Duration hour = Duration.ofHours(1);
    FxRateImportProperties properties =
        new FxRateImportProperties(
            enabled,
            URI.create("https://example.test"),
            "0 0 0/2 * * ?",
            BERLIN,
            hour,
            hour,
            hour,
            hour,
            hour);
    return new AdminFxImportService(
        settingRepository,
        new FxImportScheduleService(settingRepository, properties),
        importService,
        fxRateRepository,
        properties,
        clock,
        scheduler,
        auditRepository,
        JsonMapper.builder().build(),
        new VersionPreconditionService());
  }
}
