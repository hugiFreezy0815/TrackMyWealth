package com.trackmywealth.backend.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds the active server span to request logs and the response before security runs. */
@Component
public class TraceparentResponseFilter extends OncePerRequestFilter {

  public static final String HEADER = "traceparent";
  public static final String TRACE_ID_MDC_KEY = "requestTraceId";
  private static final String TRACE_CONTEXT_ATTRIBUTE =
      TraceparentResponseFilter.class.getName() + ".traceContext";

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
    TraceContext traceContext = traceContextOf(request);
    Propagator propagator = propagators.getIfAvailable();
    if (traceContext == null || propagator == null) {
      filterChain.doFilter(request, response);
      return;
    }
    MDC.put(TRACE_ID_MDC_KEY, traceContext.traceId());
    try {
      propagator.inject(
          traceContext,
          response,
          (carrier, name, value) -> {
            if (HEADER.equalsIgnoreCase(name)) {
              carrier.setHeader(HEADER, value);
            }
          });
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(TRACE_ID_MDC_KEY);
    }
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  private TraceContext traceContextOf(HttpServletRequest request) {
    if (request.getAttribute(TRACE_CONTEXT_ATTRIBUTE) instanceof TraceContext saved) {
      return saved;
    }
    // The server observation has already closed on a separate container error dispatch.
    if (request.getDispatcherType() == DispatcherType.ERROR) {
      return null;
    }
    Tracer tracer = tracers.getIfAvailable();
    Span span = tracer == null ? null : tracer.currentSpan();
    if (span == null) {
      return null;
    }
    TraceContext context = span.context();
    request.setAttribute(TRACE_CONTEXT_ATTRIBUTE, context);
    return context;
  }
}
