package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.web.ProblemDetails;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EPIC-29 (#149): the container's {@code /error} page in the one error shape ({@link
 * ProblemDetails}), replacing Spring Boot's {@code BasicErrorController}. It answers what fails
 * before Spring MVC can - an exception thrown inside a servlet filter, or a status the container
 * sets itself - which {@code GlobalExceptionHandler} never sees.
 *
 * <p>The detail depends on the status alone, never on the exception, so nothing internal leaks
 * (FR-API-005); a 404 says the same generic "Not found." as everywhere else (US-28-03). A 5xx is
 * logged with its cause and the correlation id.
 */
@RestController
public class ProblemErrorController implements ErrorController {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorController.class);

  @RequestMapping("${server.error.path:/error}")
  public ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
    HttpStatus status = statusOf(request);
    if (status.is5xxServerError()) {
      int code = status.value();
      if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable cause) {
        LOG.error("Error outside Spring MVC answered with {}", code, cause);
      } else {
        LOG.error("Error outside Spring MVC answered with {}", code);
      }
    }
    // The path the client asked for, not /error, which is only where the container forwarded it.
    ProblemDetail problem =
        ProblemDetails.withInstance(
            ProblemDetails.of(status, ApiErrorCode.forStatus(status), detailFor(status)),
            request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String path
                ? path
                : null);
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  private static HttpStatus statusOf(HttpServletRequest request) {
    HttpStatus status =
        request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
            ? HttpStatus.resolve(code)
            : null;
    return status != null && status.isError() ? status : HttpStatus.INTERNAL_SERVER_ERROR;
  }

  private static String detailFor(HttpStatus status) {
    if (status == HttpStatus.NOT_FOUND) {
      return "Not found.";
    }
    if (status.is4xxClientError()) {
      return "The request was rejected.";
    }
    return "An unexpected error occurred. Quote the correlation id when reporting it.";
  }
}
