package com.trackmywealth.backend.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * EPIC-29 (#149)/FR-API-005: every request has a correlation id - the caller's {@value #HEADER} if
 * it is well formed, else a fresh UUID. It is echoed on every response, carried in every error
 * body, and put in the logging MDC ({@value #MDC_KEY}), so an error a user reports can be found in
 * the logs. Runs first, before security, so even a 401 or 429 carries it.
 *
 * <p>An incoming value is only trusted as an opaque token of safe characters: anything else (too
 * long, spaces, control characters, log-injection attempts) is replaced, never echoed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

  /** The current request's correlation id, or {@code null} outside a request. */
  public static String current() {
    return MDC.get(MDC_KEY);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String correlationId =
        incoming != null && WELL_FORMED.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
