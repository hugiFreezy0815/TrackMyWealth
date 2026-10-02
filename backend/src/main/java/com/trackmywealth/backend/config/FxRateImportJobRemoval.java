package com.trackmywealth.backend.config;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * #223: with the FX import switched off ({@code app.fx.import.enabled=false}), removes the job and
 * triggers an earlier, enabled start stored in the clustered JDBC job store. {@link
 * FxRateImportJobConfig} is not loaded then, but stored triggers outlive it and would keep firing
 * the job - as no-ops, every step checks the setting - every few minutes. Switching the import on
 * again stores them anew.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.fx.import", name = "enabled", havingValue = "false")
public class FxRateImportJobRemoval {

  private static final Logger LOG = LoggerFactory.getLogger(FxRateImportJobRemoval.class);

  @Bean
  ApplicationRunner removeStoredFxRateImportJob(Scheduler scheduler) {
    return args -> removeStoredJob(scheduler);
  }

  static void removeStoredJob(Scheduler scheduler) throws SchedulerException {
    if (scheduler.deleteJob(FxRateImportJobConfig.JOB_KEY)) {
      LOG.info("FX import is disabled: removed its stored job and triggers");
    }
  }
}
