package com.trackmywealth.backend.config;

import com.trackmywealth.backend.web.CorrelationIdFilter;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * #197: work handed to an {@code @Async} method runs on another thread, and the logging MDC - among
 * it the request's correlation id ({@link CorrelationIdFilter}) - is per thread. This copies the
 * submitting thread's MDC onto the worker for the task's duration, so its log lines quote the same
 * id. Spring Boot applies a single {@link TaskDecorator} bean to its auto-configured application
 * task executor. Scheduled and Quartz jobs start outside any request and have no id to carry.
 *
 * <p>Spring Boot applies a {@link TaskDecorator} bean only while it is the only one: adding a
 * second would silently drop this one from the executor. Combine them (e.g. Spring's {@code
 * CompositeTaskDecorator}) in a single bean instead.
 */
@Component
public class MdcTaskDecorator implements TaskDecorator {

  @Override
  public Runnable decorate(Runnable task) {
    Map<String, String> submitted = MDC.getCopyOfContextMap();
    return () -> {
      Map<String, String> previous = MDC.getCopyOfContextMap();
      restore(submitted);
      try {
        task.run();
      } finally {
        restore(previous);
      }
    };
  }

  private static void restore(Map<String, String> context) {
    if (context == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(context);
    }
  }
}
