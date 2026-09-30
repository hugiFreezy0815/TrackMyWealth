package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * EPIC-29 (#149): the container's error dispatch to {@code /error} is a second pass over the same
 * request, and must carry the id the request already got - in the MDC while it runs, and on the
 * response.
 */
class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void theErrorDispatchKeepsTheRequestsCorrelationId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    String assigned = response.getHeader(CorrelationIdFilter.HEADER);
    assertThat(assigned).hasSize(36);
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();

    request.setDispatcherType(DispatcherType.ERROR);
    MockHttpServletResponse errorResponse = new MockHttpServletResponse();
    AtomicReference<String> seenByErrorPage = new AtomicReference<>();
    filter.doFilter(
        request,
        errorResponse,
        (req, res) -> seenByErrorPage.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

    assertThat(seenByErrorPage.get()).isEqualTo(assigned);
    assertThat(errorResponse.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(assigned);
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void aMalformedIncomingIdIsReplaced() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
    request.addHeader(CorrelationIdFilter.HEADER, "bad id\r\nX-Injected: 1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getHeader(CorrelationIdFilter.HEADER)).hasSize(36).doesNotContain(" ");
  }
}
