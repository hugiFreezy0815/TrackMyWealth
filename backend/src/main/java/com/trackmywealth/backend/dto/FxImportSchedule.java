package com.trackmywealth.backend.dto;

/**
 * The FX import schedule in force (US-06-07, #227), as {@code FxImportScheduleService} resolves it
 * from the stored setting and the environment.
 *
 * @param intervalHours the interval in hours; {@code null} when the environment's cron is not one
 *     of the allowed intervals (an operator's own cron)
 * @param source whether an administrator set it, or the environment applies
 * @param cron the Quartz cron the scheduled import runs on
 * @param version the setting's version, for If-Match
 */
public record FxImportSchedule(
    Integer intervalHours, FxImportIntervalSource source, String cron, int version) {

  public boolean setByAdministrator() {
    return source == FxImportIntervalSource.ADMINISTRATOR;
  }
}
