package com.trackmywealth.backend.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the one error shape ({@link ProblemDetails}) from a filter or a Spring Security handler,
 * where no Spring MVC message converter runs: a 401, a 403 decided by the security chain, a 429.
 */
@Component
public class ProblemResponseWriter {

  private final JsonMapper jsonMapper;

  public ProblemResponseWriter(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  public void write(HttpServletResponse response, HttpStatusCode status, String code, String detail)
      throws IOException {
    ProblemDetail problem = ProblemDetails.of(status, code, detail);
    response.setStatus(status.value());
    // The same content type Spring MVC sends; JSON is UTF-8 by definition (RFC 8259), so the bytes
    // are written as such rather than through the servlet's ISO-8859-1 default writer.
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    jsonMapper.writeValue(response.getOutputStream(), ProblemDetails.asMap(problem));
  }
}
