package com.trackmywealth.backend.config;

import com.trackmywealth.backend.job.FxRateImportJob;
import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the FX import's Quartz job (US-06-04, #223) - the first job on the clustered JDBC job
 * store V90 created for EPIC 30. Spring Boot schedules every {@link JobDetail} and {@link Trigger}
 * bean; {@code spring.quartz.overwrite-existing-jobs} makes a changed schedule replace the stored
 * one on the next start. Not registered at all with {@code app.fx.import.enabled=false}.
 *
 * <p>One job with three triggers, so its runs never overlap ({@link FxRateImportJob}). A missed
 * scheduled firing (the application was down) fires once as soon as the scheduler is back; the
 * import itself resumes from the last stored day, so it never loses one.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.fx.import", name = "enabled", havingValue = "true")
public class FxRateImportJobConfig {

  public static final String JOB_GROUP = "fx-rate-import";
  public static final JobKey JOB_KEY = JobKey.jobKey("import", JOB_GROUP);

  @Bean
  JobDetail fxRateImportJobDetail() {
    return JobBuilder.newJob(FxRateImportJob.class)
        .withIdentity(JOB_KEY)
        .withDescription("Imports the ECB FX rates, their history and cross rates (#223)")
        .storeDurably()
        .build();
  }

  // Every two hours by default (product owner, 2026-10-02).
  @Bean
  Trigger fxRateScheduledImportTrigger(
      JobDetail fxRateImportJobDetail, FxRateImportProperties properties) {
    return TriggerBuilder.newTrigger()
        .forJob(fxRateImportJobDetail)
        .withIdentity("scheduled-import", JOB_GROUP)
        .usingJobData(FxRateImportJob.MODE, FxRateImportJob.IMPORT)
        .withSchedule(
            CronScheduleBuilder.cronSchedule(properties.importCron())
                .inTimeZone(TimeZone.getTimeZone(properties.importCronZone()))
                .withMisfireHandlingInstructionFireAndProceed())
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
