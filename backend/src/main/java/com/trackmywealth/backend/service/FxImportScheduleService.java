package com.trackmywealth.backend.service;

import com.trackmywealth.backend.config.FxRateImportProperties;
import com.trackmywealth.backend.dto.FxImportIntervalSource;
import com.trackmywealth.backend.dto.FxImportSchedule;
import com.trackmywealth.backend.entity.FxImportSetting;
import com.trackmywealth.backend.repository.FxImportSettingRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * When the scheduled FX import runs (US-06-07, #227): every 1, 2, 6, 12 or 24 hours as set by an
 * administrator ({@code fx_import_setting}, V61), otherwise the deployment's {@code
 * app.fx.import.import-cron}. The stored interval wins at every start, so a restart never undoes
 * it.
 *
 * <p>An interval runs clock-aligned in {@code app.fx.import.import-cron-zone}, in the cron style
 * the default already uses ({@code 0 0 0/2 * * ?}): every 6 hours is 00:00, 06:00, 12:00 and 18:00,
 * every 24 hours is midnight. An autumn DST fall-back can make the elapsed time between local clock
 * boundaries one hour longer; {@link AdminFxImportService} therefore adds a one-shot deadline
 * import after a runtime change only when the next cron firing would exceed the selected interval.
 *
 * <p>Kept apart from {@link AdminFxImportService}, which reschedules through the Quartz {@code
 * Scheduler}: the scheduled-import trigger bean reads this service while the scheduler is being
 * built, so this service must not depend on the scheduler.
 */
@Service
public class FxImportScheduleService {

  /** The intervals an administrator may choose, in hours. */
  public static final List<Integer> ALLOWED_INTERVAL_HOURS = List.of(1, 2, 6, 12, 24);

  private static final int DAILY = 24;

  private final FxImportSettingRepository settingRepository;
  private final FxRateImportProperties properties;

  public FxImportScheduleService(
      FxImportSettingRepository settingRepository, FxRateImportProperties properties) {
    this.settingRepository = settingRepository;
    this.properties = properties;
  }

  @Transactional(readOnly = true)
  public FxImportSchedule current() {
    return scheduleOf(theSetting());
  }

  /** The one stored setting row, which V61 creates. */
  FxImportSetting theSetting() {
    return settingRepository
        .findTheSetting()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "fx_import_setting has no row: V61 creates it, so it was deleted by hand"));
  }

  FxImportSchedule scheduleOf(FxImportSetting setting) {
    int version =
        VersionPreconditionService.persistedVersion(setting.getVersion(), "FX import setting");
    Integer stored = setting.getImportIntervalHours();
    if (stored != null) {
      return new FxImportSchedule(
          stored, FxImportIntervalSource.ADMINISTRATOR, cronFor(stored), version);
    }
    String environmentCron = properties.importCron();
    return new FxImportSchedule(
        intervalHoursOf(environmentCron),
        FxImportIntervalSource.ENVIRONMENT,
        environmentCron,
        version);
  }

  /** The clock-aligned Quartz cron for an interval of {@code hours}. */
  public static String cronFor(int hours) {
    if (!ALLOWED_INTERVAL_HOURS.contains(hours)) {
      throw new IllegalArgumentException("Not an allowed FX import interval: " + hours);
    }
    // Hours run 0-23, so "0/24" is no valid increment; once a day is simply midnight.
    return hours == DAILY ? "0 0 0 * * ?" : "0 0 0/" + hours + " * * ?";
  }

  /**
   * The interval {@code cron} stands for, if it is exactly what {@link #cronFor} builds for one of
   * the allowed intervals (blanks between fields aside); {@code null} for any other cron.
   */
  static Integer intervalHoursOf(String cron) {
    String normalized = String.join(" ", cron.trim().split("\\s+"));
    return ALLOWED_INTERVAL_HOURS.stream()
        .filter(hours -> cronFor(hours).equals(normalized))
        .findFirst()
        .orElse(null);
  }
}
