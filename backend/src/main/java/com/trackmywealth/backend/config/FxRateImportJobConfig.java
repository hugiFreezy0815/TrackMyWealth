package com.trackmywealth.backend.config;

import com.trackmywealth.backend.job.FxRateDailyImportJob;
import com.trackmywealth.backend.job.FxRateHistoryCheckJob;
import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the FX import's Quartz jobs (US-06-04, #223) - the first jobs on the clustered JDBC job
 * store V90 created for EPIC 30. Spring Boot schedules every {@link JobDetail} and {@link Trigger}
 * bean; {@code spring.quartz.overwrite-existing-jobs} makes a changed schedule replace the stored
 * one on the next start. Not registered at all with {@code app.fx.import.enabled=false}.
 *
 * <p>A missed daily firing (the application was down at 16:30) fires once as soon as the scheduler
 * is back; the import itself resumes from the last stored day, so it never loses one.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.fx.import", name = "enabled", havingValue = "true")
public class FxRateImportJobConfig {

  public static final String JOB_GROUP = "fx-rate-import";

  @Bean
  JobDetail fxRateDailyImportJobDetail() {
    return JobBuilder.newJob(FxRateDailyImportJob.class)
        .withIdentity("daily-import", JOB_GROUP)
        .withDescription("Imports the latest ECB FX rates and any missing history (#223)")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger fxRateDailyImportTrigger(
      JobDetail fxRateDailyImportJobDetail, FxRateImportProperties properties) {
    return TriggerBuilder.newTrigger()
        .forJob(fxRateDailyImportJobDetail)
        .withIdentity("daily-import", JOB_GROUP)
        .withSchedule(
            CronScheduleBuilder.cronSchedule(properties.dailyCron())
                .inTimeZone(TimeZone.getTimeZone(properties.dailyCronZone()))
                .withMisfireHandlingInstructionFireAndProceed())
        .build();
  }

  // Once at every start: a fresh installation loads its history straight away rather than at the
  // next 16:30, and one that was down catches up. The import resumes from the last stored day, so
  // an extra run costs one small provider call at most.
  @Bean
  Trigger fxRateStartupImportTrigger(JobDetail fxRateDailyImportJobDetail) {
    return TriggerBuilder.newTrigger()
        .forJob(fxRateDailyImportJobDetail)
        .withIdentity("startup-import", JOB_GROUP)
        .startNow()
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
        .build();
  }

  @Bean
  JobDetail fxRateHistoryCheckJobDetail() {
    return JobBuilder.newJob(FxRateHistoryCheckJob.class)
        .withIdentity("history-check", JOB_GROUP)
        .withDescription("Loads older FX history once an older transaction is booked (#223)")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger fxRateHistoryCheckTrigger(
      JobDetail fxRateHistoryCheckJobDetail, FxRateImportProperties properties) {
    long intervalMillis = properties.historyCheckInterval().toMillis();
    return TriggerBuilder.newTrigger()
        .forJob(fxRateHistoryCheckJobDetail)
        .withIdentity("history-check", JOB_GROUP)
        .startAt(Date.from(Instant.now().plusMillis(intervalMillis)))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInMilliseconds(intervalMillis)
                .repeatForever()
                .withMisfireHandlingInstructionNextWithRemainingCount())
        .build();
  }
}
