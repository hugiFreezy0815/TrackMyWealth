package com.trackmywealth.backend.config;

import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Resolves caller-visible validation messages independently of the JVM default locale (#153).
 *
 * <p>An authenticated user's persisted EN/DE preference is authoritative. Before authentication
 * (setup/login and malformed credentials), {@code Accept-Language} is used. Only English and
 * German are supported at launch; a missing or unsupported header deterministically falls back to
 * English. Regional variants such as {@code de-CH} and {@code en-GB} resolve to their supported
 * base language.
 */
public class RequestLocaleResolver extends AcceptHeaderLocaleResolver {

  public RequestLocaleResolver() {
    setSupportedLocales(List.of(Locale.ENGLISH, Locale.GERMAN));
    setDefaultLocale(Locale.ENGLISH);
  }

  @Override
  public Locale resolveLocale(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal) {
      return "DE".equals(principal.language()) ? Locale.GERMAN : Locale.ENGLISH;
    }
    return super.resolveLocale(request);
  }
}
