package com.trackmywealth.backend.config;

import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Locale;
import org.springframework.lang.Nullable;
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
public class RequestLocaleResolver implements LocaleResolver {

  @Override
  public Locale resolveLocale(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal) {
      return localeForLanguage(principal.language());
    }

    return Collections.list(request.getLocales()).stream()
        .map(Locale::getLanguage)
        .filter(language -> "de".equalsIgnoreCase(language) || "en".equalsIgnoreCase(language))
        .findFirst()
        .map(RequestLocaleResolver::localeForLanguage)
        .orElse(Locale.ENGLISH);
  }

  @Override
  public void setLocale(
      HttpServletRequest request, HttpServletResponse response, @Nullable Locale locale) {
    throw new UnsupportedOperationException("Request locale is read-only");
  }

  private static Locale localeForLanguage(String language) {
    return "de".equalsIgnoreCase(language) ? Locale.GERMAN : Locale.ENGLISH;
  }
}
