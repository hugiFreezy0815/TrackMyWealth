package com.trackmywealth.backend.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.fixtures.contract.DocumentedController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * US-29-02 (#372): a documented operation answering a success status the contract does not list for
 * it fails the request - and (US-29-09, #455) one whose success response lacks a header the
 * contract declares for it, or carries one it does not; every other response passes through
 * unchanged.
 */
class DocumentedSuccessStatusFilterTest {

  private final DocumentedSuccessStatusFilter filter = new DocumentedSuccessStatusFilter();

  private static final Set<ResponseHeader> BOTH = Set.of(ResponseHeader.values());
  private static final Set<ResponseHeader> NONE = Set.of();

  @Test
  void aDocumentedStatusPassesWithItsBodyAndHeaders() throws Exception {
    MockHttpServletResponse response = run("createFixture", HttpStatus.CREATED, BOTH);

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(response.getHeader("Location")).isEqualTo("/fixture/1");
    assertThat(response.getContentAsString()).isEqualTo("the body");
  }

  @Test
  void aStatusTheHandlerDoesNotDeclareFailsTheRequestNamingIt() throws Exception {
    MockHttpServletResponse response = run("createFixture", HttpStatus.OK, BOTH);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getHeader("ETag")).isNull();
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .startsWith("DocumentedController#createFixture answered 200 OK")
        .contains("documents [201 CREATED]")
        .doesNotContain("the body");
  }

  @Test
  void aHandlerWithoutSuccessStatusMayAnswerOnly200() throws Exception {
    assertThat(run("getFixture", HttpStatus.OK, NONE).getStatus()).isEqualTo(200);
    assertThat(run("getFixture", HttpStatus.NO_CONTENT, NONE).getStatus()).isEqualTo(500);
  }

  @Test
  void aHeaderTheContractDoesNotDeclareFailsTheRequestNamingIt() throws Exception {
    MockHttpServletResponse response =
        run("getFixture", HttpStatus.OK, Set.of(ResponseHeader.ETAG));

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getHeader("ETag")).isNull();
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .startsWith("DocumentedController#getFixture answered 200 OK with the ETag header")
        .contains("does not document it")
        .doesNotContain("the body");
  }

  @Test
  void aDeclaredHeaderTheResponseLacksFailsTheRequestNamingIt() throws Exception {
    MockHttpServletResponse response =
        run("createFixture", HttpStatus.CREATED, Set.of(ResponseHeader.ETAG));

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .startsWith("DocumentedController#createFixture answered 201 CREATED without the Location")
        .contains("documents it");
  }

  @Test
  void aVersionedReadSendsItsETag() throws Exception {
    assertThat(run("getVersionedFixture", HttpStatus.OK, Set.of(ResponseHeader.ETAG)).getStatus())
        .isEqualTo(200);
    assertThat(run("getVersionedFixture", HttpStatus.OK, NONE).getStatus()).isEqualTo(500);
  }

  @Test
  void errorsAndHiddenHandlersAreNotChecked() throws Exception {
    assertThat(run("createFixture", HttpStatus.CONFLICT, NONE).getStatus()).isEqualTo(409);
    assertThat(run("internal", HttpStatus.ACCEPTED, BOTH).getStatus()).isEqualTo(202);
  }

  @Test
  void anUndeclaredNullFoundInTheBodyFailsTheRequestNamingIt() throws Exception {
    MockHttpServletResponse response =
        run(
            "getFixture",
            HttpStatus.OK,
            NONE,
            "Fixture#getFixture answered null in [Fixture.name]");

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

  private MockHttpServletResponse run(
      String handlerName, HttpStatus status, Set<ResponseHeader> headers) throws Exception {
    return run(handlerName, status, headers, null);
  }

  private MockHttpServletResponse run(
      String handlerName, HttpStatus status, Set<ResponseHeader> headers, String nullFinding)
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
          if (headers.contains(ResponseHeader.ETAG)) {
            http.setHeader("ETag", "\"1\"");
          }
          if (headers.contains(ResponseHeader.LOCATION)) {
            http.setHeader("Location", "/fixture/1");
          }
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
