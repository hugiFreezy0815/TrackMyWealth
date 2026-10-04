package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.config.FxRateImportProperties;
import com.trackmywealth.backend.dto.FxImportIntervalSource;
import com.trackmywealth.backend.dto.FxImportSchedule;
import com.trackmywealth.backend.entity.FxImportSetting;
import com.trackmywealth.backend.repository.FxImportSettingRepository;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.CronExpression;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * US-06-07 (#227): the interval an administrator chooses becomes a clock-aligned cron, the stored
 * interval wins over the environment's cron, and an operator's own cron is reported as such.
 */
class FxImportScheduleServiceTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final String ENVIRONMENT_CRON = "0 0 0/12 * * ?";

  private final FxImportSettingRepository repository = mock(FxImportSettingRepository.class);

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 6, 12, 24})
  void everyAllowedIntervalRunsClockAlignedThatManyHoursApart(int hours) throws Exception {
    CronExpression cron = new CronExpression(FxImportScheduleService.cronFor(hours));
    cron.setTimeZone(TimeZone.getTimeZone(BERLIN));

    // A day without a DST switch: the runs fall on whole hours divisible by the interval.
    ZonedDateTime from = ZonedDateTime.of(2026, 10, 7, 0, 30, 0, 0, BERLIN);
    Date first = cron.getNextValidTimeAfter(Date.from(from.toInstant()));
    Date second = cron.getNextValidTimeAfter(first);

    ZonedDateTime firstRun = first.toInstant().atZone(BERLIN);
    assertThat(firstRun.getMinute()).isZero();
    assertThat(firstRun.getHour() % hours).isZero();
    assertThat(Duration.between(first.toInstant(), second.toInstant()))
        .isEqualTo(Duration.ofHours(hours));
    // The AC: the next run is never more than one interval after a change.
    assertThat(Duration.between(from.toInstant(), first.toInstant()))
        .isLessThanOrEqualTo(Duration.ofHours(hours));
  }

  @Test
  void onceADayIsMidnightAndAnythingElseIsNoAllowedInterval() {
    assertThat(FxImportScheduleService.cronFor(24)).isEqualTo("0 0 0 * * ?");
    assertThat(FxImportScheduleService.cronFor(2)).isEqualTo("0 0 0/2 * * ?");
    assertThatThrownBy(() -> FxImportScheduleService.cronFor(5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCronIsReadBackAsItsIntervalOnlyIfItIsExactlyOneOfTheAllowedOnes() {
    assertThat(FxImportScheduleService.intervalHoursOf("0 0 0/6 * * ?")).isEqualTo(6);
    assertThat(FxImportScheduleService.intervalHoursOf(" 0  0 0/2 * *   ? ")).isEqualTo(2);
    assertThat(FxImportScheduleService.intervalHoursOf("0 0 0 * * ?")).isEqualTo(24);
    // An operator's own cron: shown as such, no interval.
    assertThat(FxImportScheduleService.intervalHoursOf("0 30 16 ? * MON-FRI")).isNull();
    assertThat(FxImportScheduleService.intervalHoursOf("0 0 0/3 * * ?")).isNull();
  }

  @Test
  void theEnvironmentsCronAppliesUntilAnAdministratorSetsAnInterval() {
    when(repository.findTheSetting()).thenReturn(Optional.of(setting(null, 0)));

    assertThat(service(ENVIRONMENT_CRON).current())
        .isEqualTo(
            new FxImportSchedule(12, FxImportIntervalSource.ENVIRONMENT, ENVIRONMENT_CRON, 0));
    assertThat(service("0 30 16 ? * MON-FRI").current())
        .isEqualTo(
            new FxImportSchedule(
                null, FxImportIntervalSource.ENVIRONMENT, "0 30 16 ? * MON-FRI", 0));
  }

  @Test
  void anIntervalSetByAnAdministratorWinsOverTheEnvironment() {
    when(repository.findTheSetting()).thenReturn(Optional.of(setting(6, 3)));

    assertThat(service(ENVIRONMENT_CRON).current())
        .isEqualTo(
            new FxImportSchedule(6, FxImportIntervalSource.ADMINISTRATOR, "0 0 0/6 * * ?", 3));
  }

  @Test
  void aMissingSettingRowIsReportedRatherThanTreatedAsNotSet() {
    when(repository.findTheSetting()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service(ENVIRONMENT_CRON).current())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fx_import_setting");
  }

  private FxImportScheduleService service(String environmentCron) {
    Duration hour = Duration.ofHours(1);
    return new FxImportScheduleService(
        repository,
        new FxRateImportProperties(
            true, "ecb", environmentCron, BERLIN, hour, hour, hour, hour, hour));
  }

  static FxImportSetting setting(Integer intervalHours, int version) {
    FxImportSetting setting = new FxImportSetting();
    if (intervalHours != null) {
      setting.setImportIntervalHours(intervalHours);
    }
    ReflectionTestUtils.setField(setting, "version", version);
    return setting;
  }
}
