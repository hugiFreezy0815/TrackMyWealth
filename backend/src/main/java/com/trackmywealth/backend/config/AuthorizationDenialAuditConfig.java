package com.trackmywealth.backend.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated bounded executor for authorization-denial persistence (#205).
 *
 * <p>It intentionally does not use the application's generic async executor: denial bursts must
 * not consume unrelated background capacity. Rejection is handled by AuthorizationDenialAuditService
 * and never falls back to the request thread, because caller-runs would recreate the connection-pool
 * starvation this executor exists to remove.
 */
@Configuration
public class AuthorizationDenialAuditConfig {

  public static final String EXECUTOR_BEAN = "authorizationDenialAuditExecutor";

  @Bean(name = EXECUTOR_BEAN)
  Executor authorizationDenialAuditExecutor(AuthorizationDenialAuditProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.writerThreads());
    executor.setMaxPoolSize(properties.writerThreads());
    executor.setQueueCapacity(properties.queueCapacity());
    executor.setThreadNamePrefix("denial-audit-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(5);
    return executor;
  }
}
