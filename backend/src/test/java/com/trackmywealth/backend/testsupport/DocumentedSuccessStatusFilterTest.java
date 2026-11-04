package com.trackmywealth.backend.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.fixtures.contract.DocumentedController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * US-29-02 (#372): a documented operation answering a success status the contract does not list for
 * it fails the request; every other response passes through unchanged.
 */
class DocumentedSuccessStatusFilterTest {

  private final DocumentedSuccessStatusFilter filter = new DocumentedSuccessStatusFilter();

  @Test
  void aDocumentedStatusPassesWithItsBodyAndHeaders() throws Exception {
    MockHttpServletResponse response = run("createFixture", HttpStatus.CREATED);

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(response.getContentAsString()).isEqualTo("the body");
  }

  @Test
  void aStatusTheHandlerDoesNotDeclareFailsTheRequestNamingIt() throws Exception {
    MockHttpServletResponse response = run("createFixture", HttpStatus.OK);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getHeader("ETag")).isNull();
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .startsWith("DocumentedController#createFixture answered 200 OK")
        .contains("documents [201 CREATED]")
        .doesNotContain("the body");
  }

  @Test
  void aHandlerWithoutSuccessStatusMayAnswerOnly200() throws Exception {
    assertThat(run("getFixture", HttpStatus.OK).getStatus()).isEqualTo(200);
    assertThat(run("getFixture", HttpStatus.NO_CONTENT).getStatus()).isEqualTo(500);
  }

  @Test
  void errorsAndHiddenHandlersAreNotChecked() throws Exception {
    assertThat(run("createFixture", HttpStatus.CONFLICT).getStatus()).isEqualTo(409);
    assertThat(run("internal", HttpStatus.ACCEPTED).getStatus()).isEqualTo(202);
  }

  @Test
  void anUndeclaredNullFoundInTheBodyFailsTheRequestNamingIt() throws Exception {
    MockHttpServletResponse response =
        run("getFixture", HttpStatus.OK, "Fixture#getFixture answered null in [Fixture.name]");

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("Fixture#getFixture answered null in [Fixture.name]");
  }

  @Test
  void anAsynchronousResponseFailsLoudlyInsteadOfLosingItsBody() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAsyncSupported(true);
    FilterChain chain = (servletRequest, servletResponse) -> servletRequest.startAsync();

    assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), chain))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("asynchronous or streaming");
  }

  private MockHttpServletResponse run(String handlerName, HttpStatus status) throws Exception {
    return run(handlerName, status, null);
  }

  private MockHttpServletResponse run(String handlerName, HttpStatus status, String nullFinding)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    HandlerMethod handler = handler(handlerName);
    FilterChain chain =
        (servletRequest, servletResponse) -> {
          servletRequest.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, handler);
          if (nullFinding != null) {
            servletRequest.setAttribute(ResponseNullabilityAdvice.FINDING, nullFinding);
          }
          HttpServletResponse http = (HttpServletResponse) servletResponse;
          http.setStatus(status.value());
          http.setHeader("ETag", "\"1\"");
          http.getWriter().write("the body");
        };
    filter.doFilter(request, response, chain);
    return response;
  }

  private static HandlerMethod handler(String name) {
    for (Method method : DocumentedController.class.getDeclaredMethods()) {
      if (method.getName().equals(name)) {
        return new HandlerMethod(new DocumentedController(), method);
      }
    }
    throw new IllegalArgumentException(name);
  }
}
