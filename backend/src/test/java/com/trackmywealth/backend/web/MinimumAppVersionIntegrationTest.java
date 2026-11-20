package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-29-16 (#576) through the real security chain: an app older than the configured minimum is
 * refused with 426 {@code APP_UPDATE_REQUIRED} before sign-in, the web build can read that answer
 * (CORS), and a caller without a version is served.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MinimumAppVersionIntegrationTest {

  private static final String ORIGIN = "http://localhost:8081";
  private static final String LOGIN = "/api/v1/auth/login";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.rate-limit.enabled", () -> "false");
    registry.add("app.client.minimum-app-version", () -> "1.2.0");
  }

  @LocalServerPort int port;

  @Test
  void anAppOlderThanTheMinimumIsAskedToUpdateBeforeSignIn() {
    login("1.1.9")
        .expectStatus()
        .isEqualTo(HttpStatus.UPGRADE_REQUIRED)
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader()
        .exists("X-Correlation-Id")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("APP_UPDATE_REQUIRED")
        .jsonPath("$.status")
        .isEqualTo(426)
        .jsonPath("$.instance")
        .isEqualTo(LOGIN);
  }

  @Test
  void theMinimumAndNewerAndACallerWithoutAVersionAreServed() {
    // Served: the unknown credentials are refused by the sign-in itself.
    login("1.2.0").expectStatus().isUnauthorized();
    login("3.0.0").expectStatus().isUnauthorized();
    login(null).expectStatus().isUnauthorized();
  }

  @Test
  void aMalformedVersionIsRefused() {
    login("1.2")
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void theProbesAreNotTheAppsAndStayReachable() {
    client()
        .get()
        .uri("/actuator/health")
        .header(AppVersion.HEADER, "0.0.1")
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void theWebBuildMaySendTheVersionAndReadTheRefusal() {
    client()
        .method(HttpMethod.OPTIONS)
        .uri(LOGIN)
        .header(HttpHeaders.ORIGIN, ORIGIN)
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-app-version,content-type")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .value(
            HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
            allowed -> assertThat(allowed.toLowerCase()).contains("x-app-version"));

    client()
        .post()
        .uri(LOGIN)
        .header(HttpHeaders.ORIGIN, ORIGIN)
        .header(AppVersion.HEADER, "1.0.0")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest("member@example.com", "correct-horse-battery-staple"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UPGRADE_REQUIRED)
        .expectHeader()
        .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN)
        .expectHeader()
        .value(
            HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
            exposed -> assertThat(exposed).contains("Deprecation", "Sunset", "Link"));
  }

  private ResponseSpec login(String version) {
    var request =
        client()
            .post()
            .uri(LOGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new LoginRequest("member@example.com", "correct-horse-battery-staple"));
    if (version != null) {
      request = request.header(AppVersion.HEADER, version);
    }
    return request.exchange();
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }
}
