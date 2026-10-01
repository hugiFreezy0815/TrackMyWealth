package com.trackmywealth.backend.config;

import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
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
public class RequestLocaleResolver implements LocaleResolver {

  @Override
  public Locale resolveLocale(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal) {
      return localeForLanguage(principal.language());
    }

    // Without the header, the Servlet spec makes getLocales() answer the *server's* default locale
    // -
    // a German JVM would turn an anonymous English caller German again (#153). Decide that case
    // here, before asking the container.
    String acceptLanguage = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
    if (acceptLanguage == null || acceptLanguage.isBlank()) {
      return Locale.ENGLISH;
    }

    return Collections.list(request.getLocales()).stream()
        .map(Locale::getLanguage)
        .filter(language -> "de".equalsIgnoreCase(language) || "en".equalsIgnoreCase(language))
        .findFirst()
        .map(RequestLocaleResolver::localeForLanguage)
        .orElse(Locale.ENGLISH);
  }

  // @Nullable as LocaleResolver declares it (JSpecify, Spring 7) - a different annotation reads as
  // tightening the interface's contract (SpotBugs NP_METHOD_PARAMETER_TIGHTENS_ANNOTATION).
  @Override
  public void setLocale(
      HttpServletRequest request, HttpServletResponse response, @Nullable Locale locale) {
    throw new UnsupportedOperationException("Request locale is read-only");
  }

  private static Locale localeForLanguage(String language) {
    return "de".equalsIgnoreCase(language) ? Locale.GERMAN : Locale.ENGLISH;
  }
}
