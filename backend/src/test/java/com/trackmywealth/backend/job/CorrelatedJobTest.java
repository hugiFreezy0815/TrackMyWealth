package com.trackmywealth.backend.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.trackmywealth.backend.web.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.slf4j.MDC;

/** #223: every job run logs under a correlation id of its own, without leaking it to the thread. */
class CorrelatedJobTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void eachRunGetsAFreshCorrelationIdThatIsRemovedAfterwards() {
    RecordingJob job = new RecordingJob();

    job.executeInternal(mock(JobExecutionContext.class));
    String first = job.seen;
    job.executeInternal(mock(JobExecutionContext.class));

    assertThat(first).matches("[0-9a-f-]{36}");
    assertThat(job.seen).matches("[0-9a-f-]{36}").isNotEqualTo(first);
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void theThreadsPreviousCorrelationIdIsRestoredEvenWhenTheRunFails() {
    MDC.put(CorrelationIdFilter.MDC_KEY, "outer");
    RecordingJob job = new RecordingJob();
    job.failure = new IllegalStateException("provider exploded");

    assertThatThrownBy(() -> job.executeInternal(mock(JobExecutionContext.class)))
        .isSameAs(job.failure);

    assertThat(job.seen).isNotEqualTo("outer");
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("outer");
  }

  private static final class RecordingJob extends CorrelatedJob {
    String seen;
    RuntimeException failure;

    @Override
    protected void run() {
      seen = MDC.get(CorrelationIdFilter.MDC_KEY);
      if (failure != null) {
        throw failure;
      }
    }
  }
}
