package com.trackmywealth.backend.web;

import com.trackmywealth.backend.error.ProblemCode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * The one error shape (EPIC-29 (#149), FR-API-005): an RFC 9457 {@code application/problem+json}
 * body - {@code status}, {@code title}, {@code detail} - extended with a stable {@code code}
 * ({@link ProblemCode}) and the request's {@code correlationId}. Used by {@link
 * GlobalExceptionHandler} for everything that reaches Spring MVC, and by the security and
 * rate-limit filters for what is answered before it.
 */
public final class ProblemDetails {

  public static final String CORRELATION_ID = "correlationId";

  private static final Logger LOG = LoggerFactory.getLogger(ProblemDetails.class);

  private ProblemDetails() {}

  /** The problem for {@code code}, answered with the code's own status. */
  public static ProblemDetail of(ProblemCode code, String detail) {
    return of(code.status(), code, detail);
  }

  /**
   * The problem for {@code code} with an explicit status, for a code answered with more than one
   * (e.g. {@code VERSION_CONFLICT}); a status the code does not list is refused.
   */
  public static ProblemDetail of(HttpStatusCode status, ProblemCode code, String detail) {
    if (!code.allows(status)) {
      throw new IllegalArgumentException(
          code + " is answered with " + code.statuses() + ", never with " + status.value());
    }
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty(ProblemCode.PROPERTY, code.name());
    return withCorrelationId(problem);
  }

  /**
   * Adds the class-level code if none is set yet, and the current request's correlation id.
   *
   * <p>A code that is not in the {@link ProblemCode} registry (set as a raw body property, which
   * bypasses the enum) is logged as the bug it is and replaced by the class-level code: a client
   * never sees a code the contract does not list (US-29-04, #374).
   */
  public static ProblemDetail decorate(ProblemDetail problem) {
    Map<String, Object> properties = problem.getProperties();
    Object code = properties == null ? null : properties.get(ProblemCode.PROPERTY);
    if (!(code instanceof String name && ProblemCode.isRegistered(name))) {
      if (code != null) {
        LOG.error("Unregistered problem code {} replaced by its status's class-level code", code);
      }
      problem.setProperty(
          ProblemCode.PROPERTY,
          ProblemCode.forStatus(HttpStatusCode.valueOf(problem.getStatus())).name());
    }
    return withCorrelationId(problem);
  }

  /**
   * The body as plain JSON-ready values, for a filter that writes the response itself (it runs
   * outside Spring MVC's message converters).
   */
  public static Map<String, Object> asMap(ProblemDetail problem) {
    Map<String, Object> body = new LinkedHashMap<>();
    // RFC 9457: an absent type means about:blank; Spring leaves it null then, and so does this.
    URI type = problem.getType();
    if (type != null) {
      body.put("type", type.toString());
    }
    body.put("title", problem.getTitle());
    body.put("status", problem.getStatus());
    if (problem.getDetail() != null) {
      body.put("detail", problem.getDetail());
    }
    URI instance = problem.getInstance();
    if (instance != null) {
      body.put("instance", instance.toString());
    }
    Map<String, Object> properties = problem.getProperties();
    if (properties != null) {
      body.putAll(properties);
    }
    return body;
  }

  /**
   * Sets {@code instance} to the request's own path (#197), as Spring MVC does for the errors it
   * renders, so every error body has the same fields whichever component wrote it. A path that is
   * not a valid URI - possible for a request the firewall rejects - leaves it unset.
   */
  public static ProblemDetail withInstance(ProblemDetail problem, String requestPath) {
    if (requestPath != null) {
      try {
        problem.setInstance(URI.create(requestPath));
      } catch (IllegalArgumentException notAUri) {
        problem.setInstance(null);
      }
    }
    return problem;
  }

  private static ProblemDetail withCorrelationId(ProblemDetail problem) {
    String correlationId = CorrelationIdFilter.current();
    if (correlationId != null) {
      problem.setProperty(CORRELATION_ID, correlationId);
    }
    return problem;
  }
}
