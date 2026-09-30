package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.web.CorrelationIdFilter;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * EPIC-29 (#149): what fails before Spring MVC - an exception inside a servlet filter, a status the
 * container sets - is rendered by {@code /error} in the one error shape, and never says more than
 * its status does (FR-API-005, US-28-03).
 */
class ProblemErrorControllerTest {

  private final ProblemErrorController controller = new ProblemErrorController();

  @AfterEach
  void clearMdc() {
    MDC.remove(CorrelationIdFilter.MDC_KEY);
  }

  @Test
  void anExceptionOutsideSpringMvcIsA500ThatSaysNothingAboutItsCause() {
    MDC.put(CorrelationIdFilter.MDC_KEY, "trace-12345678");
    MockHttpServletRequest request = errorRequest(500);
    request.setAttribute(
        RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret table name"));

    ResponseEntity<ProblemDetail> response = controller.error(request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail problem = response.getBody();
    assertThat(problem.getDetail()).doesNotContain("secret").contains("correlation id");
    assertThat(problem.getProperties())
        .containsEntry("code", "INTERNAL")
        .containsEntry("correlationId", "trace-12345678");
  }

  @Test
  void itNamesThePathTheClientAskedForNotTheErrorPage() {
    MockHttpServletRequest request = errorRequest(400);
    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/accounts;x=1");

    ProblemDetail problem = controller.error(request).getBody();

    assertThat(problem.getInstance()).hasToString("/api/v1/accounts;x=1");
  }

  @Test
  void a404IsTheGenericNotFound() {
    ProblemDetail problem = controller.error(errorRequest(404)).getBody();

    assertThat(problem.getStatus()).isEqualTo(404);
    assertThat(problem.getDetail()).isEqualTo("Not found.");
    assertThat(problem.getProperties()).containsEntry("code", "NOT_FOUND");
  }

  @Test
  void aClientErrorIsRejectedWithItsClassCode() {
    ProblemDetail problem = controller.error(errorRequest(400)).getBody();

    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.getDetail()).isEqualTo("The request was rejected.");
    assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_FAILED");
  }

  @Test
  void withoutAKnownErrorStatusItIsA500() {
    assertThat(controller.error(new MockHttpServletRequest()).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(controller.error(errorRequest(200)).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  private static MockHttpServletRequest errorRequest(int status) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
    return request;
  }
}
