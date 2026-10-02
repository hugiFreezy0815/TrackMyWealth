package com.trackmywealth.backend.job;

import com.trackmywealth.backend.web.CorrelationIdFilter;
import java.util.UUID;
import org.quartz.JobExecutionContext;
import org.slf4j.MDC;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * A Quartz job whose log lines carry a correlation id of their own, the same MDC key a request's
 * lines carry ({@link CorrelationIdFilter#MDC_KEY}), so everything one run logged - a provider
 * failure included - can be found together (EPIC-29). A Quartz worker thread has no request, so
 * each run gets a fresh id; the thread's previous MDC is restored afterwards.
 */
public abstract class CorrelatedJob extends QuartzJobBean {

  @Override
  protected final void executeInternal(JobExecutionContext context) {
    String previous = MDC.get(CorrelationIdFilter.MDC_KEY);
    MDC.put(CorrelationIdFilter.MDC_KEY, UUID.randomUUID().toString());
    try {
      run();
    } finally {
      if (previous == null) {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
      } else {
        MDC.put(CorrelationIdFilter.MDC_KEY, previous);
      }
    }
  }

  /** The job's work. */
  protected abstract void run();
}
