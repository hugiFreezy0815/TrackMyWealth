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
 *
 * <p>It also runs on the container's error dispatch to {@code /error} (registered for {@code ERROR}
 * in {@link CorrelationIdFilterRegistration}), reusing the id the original request got - kept as a
 * request attribute, since the MDC is cleared when the request's own pass ends - so an error
 * rendered there quotes the same id as the header and the log lines.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  private static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
  private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

  /** The current request's correlation id, or {@code null} outside a request. */
  public static String current() {
    return MDC.get(MDC_KEY);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = correlationIdOf(request);
    request.setAttribute(ATTRIBUTE, correlationId);
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  // The error dispatch is a second pass over the same request: it must see the same id.
  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  private static String correlationIdOf(HttpServletRequest request) {
    if (request.getAttribute(ATTRIBUTE) instanceof String assigned) {
      return assigned;
    }
    String incoming = request.getHeader(HEADER);
    return incoming != null && WELL_FORMED.matcher(incoming).matches()
        ? incoming
        : UUID.randomUUID().toString();
  }
}
