package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.job.FxRateImportJob;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #223 review: an installation that switches the FX import off keeps no stored job firing. The
 * import is off in every test context (test application.properties), as in that installation.
 */
@Testcontainers
@SpringBootTest
class FxRateImportJobRemovalTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired Scheduler scheduler;
  @Autowired ApplicationRunner removeStoredFxRateImportJob;

  @Test
  void aJobStoredWhileTheImportWasEnabledIsRemovedWithItsTriggers() throws Exception {
    // What an earlier start with the import enabled left in the job store.
    scheduler.addJob(
        JobBuilder.newJob(FxRateImportJob.class)
            .withIdentity(FxRateImportJobConfig.JOB_KEY)
            .storeDurably()
            .build(),
        true);
    scheduler.scheduleJob(
        TriggerBuilder.newTrigger()
            .forJob(FxRateImportJobConfig.JOB_KEY)
            .withIdentity("history-check", FxRateImportJobConfig.JOB_GROUP)
            .startAt(new java.util.Date(System.currentTimeMillis() + 3_600_000))
            .withSchedule(SimpleScheduleBuilder.repeatHourlyForever())
            .build());

    removeStoredFxRateImportJob.run(null);

    assertThat(scheduler.checkExists(FxRateImportJobConfig.JOB_KEY)).isFalse();
    assertThat(scheduler.getTriggersOfJob(FxRateImportJobConfig.JOB_KEY)).isEmpty();
  }
}
