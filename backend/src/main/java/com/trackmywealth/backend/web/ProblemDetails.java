package com.trackmywealth.backend.web;

import com.trackmywealth.backend.error.ApiErrorCode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * The one error shape (EPIC-29 (#149), FR-API-005): an RFC 9457 {@code application/problem+json}
 * body - {@code status}, {@code title}, {@code detail} - extended with a stable {@code code}
 * ({@link ApiErrorCode}) and the request's {@code correlationId}. Used by {@link
 * GlobalExceptionHandler} for everything that reaches Spring MVC, and by the security and
 * rate-limit filters for what is answered before it.
 */
public final class ProblemDetails {

  public static final String CORRELATION_ID = "correlationId";

  private ProblemDetails() {}

  public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty(ApiErrorCode.PROPERTY, code);
    return withCorrelationId(problem);
  }

  /** Adds the class-level code if none is set yet, and the current request's correlation id. */
  public static ProblemDetail decorate(ProblemDetail problem) {
    Map<String, Object> properties = problem.getProperties();
    if (properties == null || !properties.containsKey(ApiErrorCode.PROPERTY)) {
      problem.setProperty(
          ApiErrorCode.PROPERTY,
          ApiErrorCode.forStatus(HttpStatusCode.valueOf(problem.getStatus())));
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
