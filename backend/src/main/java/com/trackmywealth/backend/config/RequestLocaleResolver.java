package com.trackmywealth.backend.config;

import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Enumeration;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Resolves caller-visible validation messages independently of the JVM default locale (#153).
 *
 * <p>An authenticated user's persisted EN/DE preference is authoritative. Before authentication
 * (setup/login and malformed credentials), {@code Accept-Language} is used. Only English and German
 * are supported at launch; a missing or unsupported header deterministically falls back to English.
 * Regional variants such as {@code de-CH} and {@code en-GB} resolve to their supported base
 * language.
 */
public final class RequestLocaleResolver implements LocaleResolver {

  @Override
  public Locale resolveLocale(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal) {
      return "DE".equals(principal.language()) ? Locale.GERMAN : Locale.ENGLISH;
    }

    String acceptLanguage = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
    if (acceptLanguage == null || acceptLanguage.isBlank()) {
      return Locale.ENGLISH;
    }

    Enumeration<Locale> requestedLocales = request.getLocales();
    while (requestedLocales.hasMoreElements()) {
      String language = requestedLocales.nextElement().getLanguage();
      if (Locale.GERMAN.getLanguage().equals(language)) {
        return Locale.GERMAN;
      }
      if (Locale.ENGLISH.getLanguage().equals(language)) {
        return Locale.ENGLISH;
      }
    }
    return Locale.ENGLISH;
  }

  @Override
  public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
    throw new UnsupportedOperationException(
        "Locale changes are not supported; locale comes from user preference or Accept-Language.");
  }
}
