package com.trackmywealth.backend.config;

import com.trackmywealth.backend.job.FxRateImportJob;
import com.trackmywealth.backend.service.FxImportScheduleService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the FX import's Quartz job (US-06-04, #223) - the first job on the clustered JDBC job
 * store V90 created for EPIC 30. Spring Boot schedules every {@link JobDetail} and {@link Trigger}
 * bean; {@code spring.quartz.overwrite-existing-jobs} makes a changed schedule replace the stored
 * one on the next start. Not registered at all with {@code app.fx.import.enabled=false}.
 *
 * <p>One job has three regular triggers plus an optional one-shot deadline trigger after a runtime
 * interval change that crosses the autumn DST fall-back. Its runs never overlap ({@link
 * FxRateImportJob}). A missed scheduled firing (the application was down) fires once as soon as the
 * scheduler is back; the import itself resumes from the last stored day, so it never loses one.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.fx.import", name = "enabled", havingValue = "true")
public class FxRateImportJobConfig {

  public static final String JOB_GROUP = "fx-rate-import";
  public static final JobKey JOB_KEY = JobKey.jobKey("import", JOB_GROUP);
  public static final TriggerKey SCHEDULED_IMPORT_TRIGGER_KEY =
      TriggerKey.triggerKey("scheduled-import", JOB_GROUP);
  public static final TriggerKey SCHEDULED_IMPORT_DEADLINE_TRIGGER_KEY =
      TriggerKey.triggerKey("scheduled-import-deadline", JOB_GROUP);

  @Bean
  JobDetail fxRateImportJobDetail() {
    return JobBuilder.newJob(FxRateImportJob.class)
        .withIdentity(JOB_KEY)
        .withDescription(
            "Imports the configured provider's FX rates, their history and cross rates (#223)")
        .storeDurably()
        .build();
  }

  // Every two hours by default (product owner, 2026-10-02); an administrator's interval wins over
  // the environment's cron (US-06-07, #227), so a restart keeps it.
  @Bean
  Trigger fxRateScheduledImportTrigger(
      FxImportScheduleService scheduleService, FxRateImportProperties properties) {
    return scheduledImportTrigger(scheduleService.current().cron(), properties.importCronZone());
  }

  /**
   * The scheduled-import trigger for {@code cron}: built here at start and by {@code
   * AdminFxImportService} when an administrator changes the interval, so both are the same trigger.
   */
  public static Trigger scheduledImportTrigger(String cron, ZoneId zone) {
    return TriggerBuilder.newTrigger()
        .forJob(JOB_KEY)
        .withIdentity(SCHEDULED_IMPORT_TRIGGER_KEY)
        .usingJobData(FxRateImportJob.MODE, FxRateImportJob.IMPORT)
        .withSchedule(
            CronScheduleBuilder.cronSchedule(cron)
                .inTimeZone(TimeZone.getTimeZone(zone))
                .withMisfireHandlingInstructionFireAndProceed())
        .build();
  }

  /**
   * One extra import at {@code deadline}, used only when a DST fall-back would otherwise make the
   * next clock-aligned cron firing later than the administrator's selected elapsed-time interval.
   */
  public static Trigger scheduledImportDeadlineTrigger(Instant deadline) {
    return TriggerBuilder.newTrigger()
        .forJob(JOB_KEY)
        .withIdentity(SCHEDULED_IMPORT_DEADLINE_TRIGGER_KEY)
        .usingJobData(FxRateImportJob.MODE, FxRateImportJob.IMPORT)
        .startAt(Date.from(deadline))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
        .build();
  }

  // Once at every start: a fresh installation loads its history straight away rather than at the
  // next scheduled run, and one that was down catches up. The import resumes from the last stored
  // day, so an extra run costs one small provider call at most.
  @Bean
  Trigger fxRateStartupImportTrigger(JobDetail fxRateImportJobDetail) {
    return TriggerBuilder.newTrigger()
        .forJob(fxRateImportJobDetail)
        .withIdentity("startup-import", JOB_GROUP)
        .usingJobData(FxRateImportJob.MODE, FxRateImportJob.IMPORT)
        .startNow()
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
        .build();
  }

  // Loads the history an older booking needs soon after it is booked; no provider call otherwise.
  @Bean
  Trigger fxRateHistoryCheckTrigger(
      JobDetail fxRateImportJobDetail, FxRateImportProperties properties) {
    long intervalMillis = properties.historyCheckInterval().toMillis();
    return TriggerBuilder.newTrigger()
        .forJob(fxRateImportJobDetail)
        .withIdentity("history-check", JOB_GROUP)
        .usingJobData(FxRateImportJob.MODE, FxRateImportJob.HISTORY_CHECK)
        .startAt(Date.from(Instant.now().plusMillis(intervalMillis)))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInMilliseconds(intervalMillis)
                .repeatForever()
                .withMisfireHandlingInstructionNextWithRemainingCount())
        .build();
  }
}
