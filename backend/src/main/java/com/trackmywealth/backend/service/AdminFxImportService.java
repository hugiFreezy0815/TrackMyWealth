package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.FxRateImportJobConfig;
import com.trackmywealth.backend.config.FxRateImportProperties;
import com.trackmywealth.backend.dto.FxImportSchedule;
import com.trackmywealth.backend.dto.FxImportSettingsResponse;
import com.trackmywealth.backend.dto.UpdateFxImportIntervalRequest;
import com.trackmywealth.backend.entity.AdminAuditLog;
import com.trackmywealth.backend.entity.FxImportSetting;
import com.trackmywealth.backend.repository.AdminAuditLogRepository;
import com.trackmywealth.backend.repository.FxImportSettingRepository;
import com.trackmywealth.backend.repository.FxRateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * US-06-07 (#227): an administrator reads and changes how often the FX rates are imported, without
 * a redeployment. Reachable only by {@code SYSTEM_ADMINISTRATOR} ({@code /api/v1/admin/**} in
 * {@code SecurityConfig}).
 *
 * <p>A change is stored ({@code fx_import_setting}), written to {@code admin_audit_log} and takes
 * effect at once: the scheduled-import trigger is rescheduled in the same transaction. The Quartz
 * JDBC job store joins Spring's transaction on the shared data source, so the setting, its audit
 * row and the stored trigger change together or not at all, and every node of the cluster runs on
 * the new trigger. At the next start {@link FxRateImportJobConfig} builds the same trigger from the
 * stored interval ({@link FxImportScheduleService}).
 */
@Service
public class AdminFxImportService {

  static final String AUDIT_ACTION = "FX_IMPORT_INTERVAL_CHANGED";

  // Names the resource in a 412 VERSION_CONFLICT detail (VersionPreconditionService).
  private static final String VERSIONED_RESOURCE = "FX import setting";

  private final FxImportSettingRepository settingRepository;
  private final FxImportScheduleService scheduleService;
  private final FxRateImportService importService;
  private final FxRateRepository fxRateRepository;
  private final FxRateImportProperties properties;
  private final Clock clock;
  private final Scheduler scheduler;
  private final AdminAuditLogRepository adminAuditLogRepository;
  private final ObjectMapper objectMapper;
  private final VersionPreconditionService versionPreconditionService;

  public AdminFxImportService(
      FxImportSettingRepository settingRepository,
      FxImportScheduleService scheduleService,
      FxRateImportService importService,
      FxRateRepository fxRateRepository,
      FxRateImportProperties properties,
      Clock clock,
      Scheduler scheduler,
      AdminAuditLogRepository adminAuditLogRepository,
      ObjectMapper objectMapper,
      VersionPreconditionService versionPreconditionService) {
    this.settingRepository = settingRepository;
    this.scheduleService = scheduleService;
    this.importService = importService;
    this.fxRateRepository = fxRateRepository;
    this.properties = properties;
    this.clock = clock;
    this.scheduler = scheduler;
    this.adminAuditLogRepository = adminAuditLogRepository;
    this.objectMapper = objectMapper;
    this.versionPreconditionService = versionPreconditionService;
  }

  @Transactional(readOnly = true)
  public FxImportSettingsResponse current() {
    FxImportSchedule schedule = scheduleService.current();
    return toResponse(schedule, properties.enabled() ? nextScheduledRun() : null);
  }

  /**
   * Sets the interval. Rejected with 409 while the import is disabled (there is no schedule to
   * change), 428/412 without the current version, 422 for a value outside {@link
   * FxImportScheduleService#ALLOWED_INTERVAL_HOURS}. Setting the interval an administrator already
   * set changes nothing and writes no audit row; setting the one the environment's cron already
   * stands for does store it, since from then on it wins over the environment.
   */
  @Transactional
  public FxImportSettingsResponse updateInterval(
      UpdateFxImportIntervalRequest request, Integer expectedVersion, UUID actorUserId) {
    if (!properties.enabled()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "The FX import is disabled in this deployment (FX_IMPORT_ENABLED=false), so it has no"
              + " schedule to change.");
    }
    FxImportSetting setting = scheduleService.theSetting();
    versionPreconditionService.requireCurrent(
        expectedVersion, setting.getVersion(), VERSIONED_RESOURCE);
    int hours = request.intervalHours();
    if (!FxImportScheduleService.ALLOWED_INTERVAL_HOURS.contains(hours)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "intervalHours must be one of " + FxImportScheduleService.ALLOWED_INTERVAL_HOURS + ".");
    }

    FxImportSchedule before = scheduleService.scheduleOf(setting);
    if (before.setByAdministrator() && Objects.equals(before.intervalHours(), hours)) {
      return toResponse(before, nextScheduledRun());
    }
    setting.setImportIntervalHours(hours);
    setting.setUpdatedBy(actorUserId);
    FxImportSchedule after = scheduleService.scheduleOf(settingRepository.saveAndFlush(setting));
    writeAuditLog(actorUserId, before, after);
    reschedule(after.cron(), hours);
    return toResponse(after, nextScheduledRun());
  }

  private void reschedule(String cron, int hours) {
    Trigger recurring =
        FxRateImportJobConfig.scheduledImportTrigger(cron, properties.importCronZone());
    // Local-clock cron stays aligned across DST. At the autumn fall-back, however, e.g. 00:00 to
    // 06:00 local is seven elapsed hours. Keep the agreed clock-aligned recurring schedule, but
    // add one one-shot import only when that next firing would miss the administrator's deadline.
    Instant deadline = clock.instant().plus(Duration.ofHours(hours));
    try {
      scheduler.unscheduleJob(FxRateImportJobConfig.SCHEDULED_IMPORT_DEADLINE_TRIGGER_KEY);
      Optional<Instant> nextRecurring =
          Optional.ofNullable(
                  scheduler.rescheduleJob(
                      FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY, recurring))
              .map(Date::toInstant);
      if (nextRecurring.isEmpty()) {
        // No stored trigger to replace (not expected while the import is enabled): schedule it
        // anew rather than let the change silently not take effect.
        nextRecurring = Optional.ofNullable(scheduler.scheduleJob(recurring)).map(Date::toInstant);
      }
      if (nextRecurring.filter(next -> next.isAfter(deadline)).isPresent()) {
        scheduler.scheduleJob(FxRateImportJobConfig.scheduledImportDeadlineTrigger(deadline));
      }
    } catch (SchedulerException e) {
      throw new IllegalStateException("Could not reschedule the FX import", e);
    }
  }

  // Read back from the job store (inside a change: the triggers just stored, same transaction).
  private OffsetDateTime nextScheduledRun() {
    try {
      Optional<Instant> nextRecurring =
          nextFireTime(FxRateImportJobConfig.SCHEDULED_IMPORT_TRIGGER_KEY);
      Optional<Instant> nextDeadline =
          nextFireTime(FxRateImportJobConfig.SCHEDULED_IMPORT_DEADLINE_TRIGGER_KEY);
      return Stream.concat(nextRecurring.stream(), nextDeadline.stream())
          .min(Comparator.naturalOrder())
          .map(next -> OffsetDateTime.ofInstant(next, zone()))
          .orElse(null);
    } catch (SchedulerException e) {
      throw new IllegalStateException("Could not read the FX import schedule", e);
    }
  }

  private Optional<Instant> nextFireTime(TriggerKey key) throws SchedulerException {
    return Optional.ofNullable(scheduler.getTrigger(key))
        .map(Trigger::getNextFireTime)
        .map(Date::toInstant);
  }

  private ZoneId zone() {
    return properties.importCronZone();
  }

  private FxImportSettingsResponse toResponse(FxImportSchedule schedule, OffsetDateTime nextRunAt) {
    return new FxImportSettingsResponse(
        properties.enabled(),
        schedule.intervalHours(),
        schedule.source(),
        schedule.cron(),
        zone().getId(),
        nextRunAt,
        fxRateRepository.findLatestRateDate(importService.source()).orElse(null),
        FxImportScheduleService.ALLOWED_INTERVAL_HOURS,
        schedule.version());
  }

  private void writeAuditLog(UUID actorUserId, FxImportSchedule before, FxImportSchedule after) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("from", auditView(before));
    details.put("to", auditView(after));
    AdminAuditLog entry = new AdminAuditLog();
    entry.setActorUserId(actorUserId);
    entry.setAction(AUDIT_ACTION);
    // Jackson 3 throws the unchecked JacksonException; a map of strings and numbers cannot fail.
    entry.setDetails(objectMapper.writeValueAsString(details));
    adminAuditLogRepository.save(entry);
  }

  // A LinkedHashMap, not Map.of: intervalHours is null for an operator's own cron.
  private static Map<String, Object> auditView(FxImportSchedule schedule) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("intervalHours", schedule.intervalHours());
    view.put("intervalSource", schedule.source().name());
    view.put("cron", schedule.cron());
    return view;
  }
}
