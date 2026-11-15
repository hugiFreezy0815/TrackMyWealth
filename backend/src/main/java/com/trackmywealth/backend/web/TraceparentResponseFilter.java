package com.trackmywealth.backend.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds the active server span to request logs and the response before security runs. */
@Component
// Spring Boot's ServerHttpObservationFilter opens the scope at HIGHEST_PRECEDENCE + 1.
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceparentResponseFilter extends OncePerRequestFilter {

  public static final String HEADER = "traceparent";
  public static final String TRACE_ID_MDC_KEY = "requestTraceId";

  private final ObjectProvider<Tracer> tracers;
  private final ObjectProvider<Propagator> propagators;

  public TraceparentResponseFilter(
      ObjectProvider<Tracer> tracers, ObjectProvider<Propagator> propagators) {
    this.tracers = tracers;
    this.propagators = propagators;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Tracer tracer = tracers.getIfAvailable();
    Span span = tracer == null ? null : tracer.currentSpan();
    Propagator propagator = propagators.getIfAvailable();
    if (span == null || propagator == null) {
      filterChain.doFilter(request, response);
      return;
    }
    try (var traceScope = MDC.putCloseable(TRACE_ID_MDC_KEY, span.context().traceId())) {
      propagator.inject(
          span.context(),
          response,
          (carrier, name, value) -> {
            if (HEADER.equalsIgnoreCase(name)) {
              carrier.setHeader(HEADER, value);
            }
          });
      filterChain.doFilter(request, response);
    }
  }
}
