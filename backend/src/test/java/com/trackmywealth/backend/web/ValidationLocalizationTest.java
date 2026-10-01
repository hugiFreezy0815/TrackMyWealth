package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trackmywealth.backend.config.RequestLocaleResolver;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ValidationLocalizationTest {

  private static Locale previousDefault;
  private final MockMvc mvc = mockMvc();

  @BeforeAll
  static void forceUnrelatedJvmLocale() {
    previousDefault = Locale.getDefault();
    Locale.setDefault(Locale.FRENCH);
  }

  @AfterAll
  static void restoreJvmLocale() {
    Locale.setDefault(previousDefault);
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void authenticatedGermanUserGetsBuiltInAndCustomMessagesInGerman() throws Exception {
    authenticate("DE");

    String body =
        mvc.perform(
                post("/validation-probe")
                    .header("Accept-Language", "en-US")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(invalidRequest()))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .contains("Die Anfrage ist ungültig.")
        .contains("muss dem Muster")
        .contains("muss eine gültige ISIN sein")
        .doesNotContain("must match")
        .doesNotContain("must be a valid ISIN");
  }

  @Test
  void authenticatedEnglishUserOverridesGermanAcceptLanguage() throws Exception {
    authenticate("EN");

    String body =
        mvc.perform(
                post("/validation-probe")
                    .header("Accept-Language", "de-CH")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(invalidRequest()))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .contains("The request is invalid.")
        .contains("must match")
        .contains("must be a valid ISIN")
        .doesNotContain("muss dem Muster")
        .doesNotContain("muss eine gültige ISIN sein");
  }

  @Test
  void anonymousGermanHeaderAndMissingHeaderAreDeterministic() throws Exception {
    String german =
        mvc.perform(
                post("/validation-probe")
                    .header("Accept-Language", "de-DE")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(invalidRequest()))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String fallback =
        mvc.perform(
                post("/validation-probe")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(invalidRequest()))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(german)
        .contains("Die Anfrage ist ungültig.")
        .contains("muss dem Muster")
        .contains("muss eine gültige ISIN sein");
    assertThat(fallback)
        .contains("The request is invalid.")
        .contains("must match")
        .contains("must be a valid ISIN");
  }

  private static MockMvc mockMvc() {
    ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasename("messages");
    messages.setDefaultEncoding("UTF-8");
    messages.setFallbackToSystemLocale(false);

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.setValidationMessageSource(messages);
    validator.afterPropertiesSet();

    // The Spring context injects the MessageSource into the advice (MessageSourceAware).
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    handler.setMessageSource(messages);

    return MockMvcBuilders.standaloneSetup(new ValidationProbeController())
        .setControllerAdvice(handler)
        .setValidator(validator)
        .setLocaleResolver(new RequestLocaleResolver())
        .build();
  }

  private static String invalidRequest() {
    return """
        {
          "isin": "IE00B4L5Y984",
          "displayName": "Bad",
          "denominationCurrency": "USD",
          "instrumentType": "ETF",
          "assetClass": "EQUITY",
          "securityCountry": "ch"
        }
        """;
  }

  private static void authenticate(String language) {
    AuthenticatedUserPrincipal principal =
        new AuthenticatedUserPrincipal(
            UUID.randomUUID(), "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID(), language);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));
  }

  @RestController
  private static class ValidationProbeController {

    @PostMapping("/validation-probe")
    void validate(@Valid @RequestBody CreateSecurityRequest request) {}
  }
}
