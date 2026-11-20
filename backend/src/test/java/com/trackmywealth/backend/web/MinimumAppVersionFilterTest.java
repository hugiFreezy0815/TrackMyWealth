package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.config.ClientVersionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class MinimumAppVersionFilterTest {

  private static final ProblemResponseWriter WRITER =
      new ProblemResponseWriter(JsonMapper.builder().build());

  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final MockFilterChain chain = new MockFilterChain();

  @Test
  void refusesAnAppOlderThanTheMinimumWithTheUpdateCode() throws Exception {
    run("1.4.0", request("GET", "/api/v1/accounts", "1.3.9"));

    assertThat(response.getStatus()).isEqualTo(426);
    assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(response.getContentAsString())
        .contains("\"code\":\"APP_UPDATE_REQUIRED\"")
        .contains("1.4.0 or newer")
        .contains("this app is 1.3.9");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void servesTheMinimumAndNewer() throws Exception {
    for (String version : new String[] {"1.4.0", "1.4.1", "1.10.0", "2.0.0"}) {
      MockFilterChain own = new MockFilterChain();
      filter("1.4.0").doFilter(request("GET", "/api/v1/accounts", version), response, own);
      assertThat(own.getRequest()).as(version).isNotNull();
    }
  }

  @Test
  void servesARequestWithoutAVersion() throws Exception {
    // PO decision on #576: no header means the caller is not the app (a script, curl).
    run("1.4.0", request("POST", "/api/v1/auth/login", null));

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void servesEveryVersionWithoutAMinimum() throws Exception {
    run(null, request("GET", "/api/v1/accounts", "0.0.1"));

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void refusesAMalformedVersionEvenWithoutAMinimum() throws Exception {
    run(null, request("GET", "/api/v1/accounts", "1.4"));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .contains("\"code\":\"VALIDATION_FAILED\"")
        .contains("X-App-Version must be MAJOR.MINOR.PATCH");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void toleratesSurroundingWhitespace() throws Exception {
    run("1.4.0", request("GET", "/api/v1/accounts", " 1.4.0 "));

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void leavesEverythingOutsideTheApiAlone() throws Exception {
    for (String path : new String[] {"/actuator/health", "/api-docs", "/error", "/api"}) {
      MockFilterChain own = new MockFilterChain();
      filter("1.4.0").doFilter(request("GET", path, "0.0.1"), response, own);
      assertThat(own.getRequest()).as(path).isNotNull();
    }
  }

  @Test
  void neverRefusesACorsPreflight() throws Exception {
    run("1.4.0", request("OPTIONS", "/api/v1/accounts", "0.0.1"));

    assertThat(chain.getRequest()).isNotNull();
  }

  private void run(String minimum, MockHttpServletRequest request) throws Exception {
    filter(minimum).doFilter(request, response, chain);
  }

  private static MinimumAppVersionFilter filter(String minimum) {
    return new MinimumAppVersionFilter(new ClientVersionProperties(minimum), WRITER);
  }

  private static MockHttpServletRequest request(String method, String path, String version) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    if (version != null) {
      request.addHeader(AppVersion.HEADER, version);
    }
    return request;
  }
}
