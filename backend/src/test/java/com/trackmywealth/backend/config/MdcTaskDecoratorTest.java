package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * #197: work handed to another thread through the application's task executor ({@code @Async}) logs
 * with the correlation id of the request that submitted it, and leaves the worker thread's MDC as
 * it found it.
 */
class MdcTaskDecoratorTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void aDecoratedTaskRunsWithTheSubmittersMdcAndRestoresTheWorkers() {
    MDC.put(CorrelationIdFilter.MDC_KEY, "request-12345678");
    Runnable decorated =
        new MdcTaskDecorator()
            .decorate(
                () ->
                    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("request-12345678"));
    MDC.clear();
    MDC.put("worker", "own");

    decorated.run();

    assertThat(MDC.get("worker")).isEqualTo("own");
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void theApplicationTaskExecutorAppliesIt() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
        .withBean(MdcTaskDecorator.class)
        .run(
            context -> {
              AsyncTaskExecutor executor =
                  context.getBean("applicationTaskExecutor", AsyncTaskExecutor.class);
              AtomicReference<String> seen = new AtomicReference<>();
              MDC.put(CorrelationIdFilter.MDC_KEY, "request-87654321");
              CompletableFuture.runAsync(
                      () -> seen.set(MDC.get(CorrelationIdFilter.MDC_KEY)), executor)
                  .get(5, TimeUnit.SECONDS);
              assertThat(seen.get()).isEqualTo("request-87654321");
            });
  }
}
