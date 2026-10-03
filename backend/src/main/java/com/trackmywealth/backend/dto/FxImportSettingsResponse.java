package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response for {@code GET}/{@code PUT /api/v1/admin/fx-import} (US-06-07, #227): how often the FX
 * rates are imported and how current they are.
 *
 * @param enabled {@code false} when the deployment switched the import off ({@code
 *     FX_IMPORT_ENABLED=false}); no run is scheduled and the interval cannot be changed then
 * @param intervalHours hours between scheduled imports, one of {@code allowedIntervalHours}; {@code
 *     null} when the deployment's own cron is not one of them
 * @param intervalSource {@code ADMINISTRATOR} when an administrator set the interval (it then wins
 *     over the environment at every start), {@code ENVIRONMENT} when {@code FX_IMPORT_CRON} applies
 * @param cron the Quartz cron (seconds first) the scheduled import runs on, for information
 * @param cronZone the zone {@code cron} is read in
 * @param nextRunAt the next scheduled run (an instant; {@code cronZone} is where its hours are
 *     counted); {@code null} while the import is disabled
 * @param lastRateDate the latest date a rate is stored for, from this import's source; {@code null}
 *     before the first import
 * @param allowedIntervalHours the intervals an administrator may choose
 * @param version the setting's version, sent back in {@code If-Match} to change it
 */
public record FxImportSettingsResponse(
    boolean enabled,
    @Schema(
            nullable = true,
            allowableValues = {"1", "2", "6", "12", "24"})
        Integer intervalHours,
    FxImportIntervalSource intervalSource,
    String cron,
    String cronZone,
    OffsetDateTime nextRunAt,
    LocalDate lastRateDate,
    List<Integer> allowedIntervalHours,
    int version) {

  public FxImportSettingsResponse {
    allowedIntervalHours = List.copyOf(allowedIntervalHours);
  }
}
