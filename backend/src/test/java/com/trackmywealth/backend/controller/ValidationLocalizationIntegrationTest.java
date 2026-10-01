package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * #153 end to end: the real validator, message source, {@code RequestLocaleResolver} and the JWT
 * filter's auth snapshot decide the language of a 400 - not the JVM, which is forced to French here
 * so no assertion can pass by accident on an English or German machine.
 *
 * <p>One request trips a built-in constraint ({@code @NotBlank}), a numeric one ({@code @Digits}),
 * a project constraint ({@code @ValidCurrencyCode}) and a constraint with its own message key
 * ({@code mcc}); every message and the problem's {@code detail} must be in one language. Bean
 * validation runs before the handler resolves the account, so a random account id suffices.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ValidationLocalizationIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String INVALID_TRANSACTION =
      """
      {"transactionType": " ", "bookingDate": "2026-09-01", "amount": "1.123456",
       "currency": "XYZ", "mcc": "12"}
      """;

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
  // The one-time setup endpoint creates the administrator once per container (i.e. per class).
  private static String adminToken;

  @LocalServerPort int port;

  @Autowired DataSource dataSource;

  @BeforeAll
  static void forceUnrelatedJvmLocale() {
    previousDefault = Locale.getDefault();
    Locale.setDefault(Locale.FRENCH);
  }

  @AfterAll
  static void restoreJvmLocale() {
    Locale.setDefault(previousDefault);
  }

  @Test
  void aGermanUserGetsEveryMessageInGermanWhateverTheHeaderSays() throws Exception {
    String body = invalidTransaction(storedLanguage("DE"), "en-US");

    assertThat(body)
        .contains("Die Anfrage ist ungültig.")
        .contains("darf nicht leer sein")
        .contains("numerischer Wert außerhalb des gültigen Bereichs")
        .contains("muss ein gültiger ISO-4217-Währungscode sein")
        .contains("muss ein vierstelliger ISO-18245-Händlerkategoriecode sein")
        .doesNotContain("The request is invalid")
        .doesNotContain("must")
        .doesNotContain("expected");
  }

  @Test
  void anEnglishUserGetsEveryMessageInEnglishWhateverTheHeaderSays() throws Exception {
    String body = invalidTransaction(storedLanguage("EN"), "de-CH");

    assertThat(body)
        .contains("The request is invalid.")
        .contains("must not be blank")
        .contains("numeric value out of bounds")
        .contains("must be a valid ISO 4217 currency code")
        .contains("must be a four-digit ISO 18245 merchant category code")
        .doesNotContain("ungültig")
        .doesNotContain("muss")
        .doesNotContain("darf");
  }

  @Test
  void anAnonymousCallerGetsTheirHeaderLanguageOrEnglish() {
    String german = invalidLogin("de-DE");
    String fallback = invalidLogin(null);

    assertThat(german).contains("Die Anfrage ist ungültig.").contains("darf nicht leer sein");
    assertThat(fallback)
        .contains("The request is invalid.")
        .contains("must not be blank")
        .doesNotContain("darf");
  }

  private String invalidTransaction(String token, String acceptLanguage) {
    return client()
        .post()
        .uri("/api/v1/accounts/" + UUID.randomUUID() + "/transactions")
        .header("Authorization", "Bearer " + token)
        .header("Accept-Language", acceptLanguage)
        .contentType(MediaType.APPLICATION_JSON)
        .body(INVALID_TRANSACTION)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST)
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  private String invalidLogin(String acceptLanguage) {
    return client()
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

  /**
   * Sets the administrator's stored language and returns their token. The auth snapshot is read per
   * request, so the change applies to the very next call - no new token needed.
   */
  private String storedLanguage(String language) throws Exception {
    if (adminToken == null) {
      adminToken =
          client()
              .post()
              .uri(SetupController.ADMINISTRATOR_PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  new SetupAdministratorRequest(
                      "admin@example.com", PASSWORD, "Test Workspace", "CHF"))
              .exchange()
              .expectStatus()
              .isEqualTo(HttpStatus.CREATED)
              .expectBody(AuthTokensResponse.class)
              .returnResult()
              .getResponseBody()
              .accessToken();
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("UPDATE app_user SET language = ?")) {
      statement.setString(1, language);
      statement.executeUpdate();
    }
    return adminToken;
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }
}
