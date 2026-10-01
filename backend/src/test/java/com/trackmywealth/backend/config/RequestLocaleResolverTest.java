package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RequestLocaleResolverTest {

  private final RequestLocaleResolver resolver = new RequestLocaleResolver();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void authenticatedStoredPreferenceOverridesAcceptLanguage() {
    authenticate("DE");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Accept-Language", "en-US,en;q=0.9");

    assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.GERMAN);
  }

  @Test
  void anonymousRegionalGermanAndEnglishHeadersUseTheirBaseLanguage() {
    MockHttpServletRequest german = new MockHttpServletRequest();
    german.addHeader("Accept-Language", "de-CH,de;q=0.9,en;q=0.8");
    MockHttpServletRequest english = new MockHttpServletRequest();
    english.addHeader("Accept-Language", "en-GB,en;q=0.9");

    assertThat(resolver.resolveLocale(german)).isEqualTo(Locale.GERMAN);
    assertThat(resolver.resolveLocale(english)).isEqualTo(Locale.ENGLISH);
  }

  @Test
  void missingOrUnsupportedLanguageFallsBackToEnglish() {
    MockHttpServletRequest missing = new MockHttpServletRequest();
    MockHttpServletRequest unsupported = new MockHttpServletRequest();
    unsupported.addHeader("Accept-Language", "fr-CH,fr;q=0.9");

    assertThat(resolver.resolveLocale(missing)).isEqualTo(Locale.ENGLISH);
    assertThat(resolver.resolveLocale(unsupported)).isEqualTo(Locale.ENGLISH);
  }

  private void authenticate(String language) {
    AuthenticatedUserPrincipal principal =
        new AuthenticatedUserPrincipal(
            UUID.randomUUID(), "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID(), language);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));
  }
}
