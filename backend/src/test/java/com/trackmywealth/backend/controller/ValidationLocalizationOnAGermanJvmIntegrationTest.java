package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #153's original symptom: a server whose JVM runs in German answered English callers in German.
 * There is no {@code messages_en.properties}, so an English lookup only reaches the English base
 * bundle because {@code spring.messages.fallback-to-system-locale} is {@code false}; with the
 * default {@code true} it would fall back to the JVM's German bundle first.
 *
 * <p>{@link ValidationLocalizationIntegrationTest} forces French, which cannot catch that: there is
 * no French bundle, so its fallback ends on the English base either way. This class forces German,
 * in a context of its own (its own container, so a fresh message-source cache), before the first
 * lookup.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ValidationLocalizationOnAGermanJvmIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  private static Locale previousDefault;

  @LocalServerPort int port;

  @BeforeAll
  static void forceGermanJvmLocale() {
    previousDefault = Locale.getDefault();
    Locale.setDefault(Locale.GERMAN);
  }

  @AfterAll
  static void restoreJvmLocale() {
    Locale.setDefault(previousDefault);
  }

  @Test
  void anEnglishCallerIsAnsweredInEnglishOnAGermanJvm() {
    for (String acceptLanguage : new String[] {"en-US", null}) {
      assertThat(invalidLogin(acceptLanguage))
          .as("Accept-Language %s", acceptLanguage)
          .contains("The request is invalid.")
          .contains("must not be blank")
          .doesNotContain("ungültig")
          .doesNotContain("darf");
    }
  }

  private String invalidLogin(String acceptLanguage) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri(AuthController.LOGIN_PATH)
        .headers(
            headers -> {
              if (acceptLanguage != null) {
                headers.set("Accept-Language", acceptLanguage);
              }
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"email\": \"\", \"password\": \"\"}")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST)
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }
}
