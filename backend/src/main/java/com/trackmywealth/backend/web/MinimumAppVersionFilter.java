package com.trackmywealth.backend.web;

import com.trackmywealth.backend.config.ClientVersionProperties;
import com.trackmywealth.backend.error.ProblemCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * US-29-16 (#576): refuses an app that is older than the configured minimum ({@link
 * ClientVersionProperties}) before anything else of the API runs, sign-in included, so an app the
 * backend no longer serves asks its member to update instead of failing on a removed element.
 *
 * <ul>
 *   <li>No {@link AppVersion#HEADER}: served. The request does not come from the app (a script,
 *       curl, another tool), and the minimum only protects against an app that is too old (PO
 *       decision on #576).
 *   <li>A header that is not {@code MAJOR.MINOR.PATCH}: 400 {@code VALIDATION_FAILED}, whether a
 *       minimum is configured or not, so a broken build is noticed before a minimum is ever set.
 *   <li>Older than the minimum: 426 {@code APP_UPDATE_REQUIRED}.
 * </ul>
 *
 * <p>Only {@code /api/} requests: the actuator probes and {@code /api-docs} are not the app's. A
 * CORS preflight is never refused, or the browser could not read the 426. Runs in the security
 * chain after its CORS filter (so the web build can read the answer) and before the rate limiter
 * and the token check (so a too-old app costs neither).
 */
@Component
public class MinimumAppVersionFilter extends OncePerRequestFilter {

  static final String API_PREFIX = "/api/";

  private static final UrlPathHelper URL_PATH_HELPER = UrlPathHelper.defaultInstance;

  private final Optional<AppVersion> minimum;
  private final ProblemResponseWriter problemResponseWriter;

  public MinimumAppVersionFilter(
      ClientVersionProperties properties, ProblemResponseWriter problemResponseWriter) {
    this.minimum = properties.minimum();
    this.problemResponseWriter = problemResponseWriter;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return HttpMethod.OPTIONS.matches(request.getMethod())
        || !URL_PATH_HELPER.getPathWithinApplication(request).startsWith(API_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String sent = request.getHeader(AppVersion.HEADER);
    if (sent == null) {
      chain.doFilter(request, response);
      return;
    }
    Optional<AppVersion> version = AppVersion.parse(sent.strip());
    if (version.isEmpty()) {
      problemResponseWriter.write(
          request,
          response,
          HttpStatus.BAD_REQUEST,
          ProblemCode.VALIDATION_FAILED,
          AppVersion.HEADER + " must be MAJOR.MINOR.PATCH, for example 1.4.0.");
      return;
    }
    if (minimum.isPresent() && version.get().isBefore(minimum.get())) {
      problemResponseWriter.write(
          request,
          response,
          HttpStatus.UPGRADE_REQUIRED,
          ProblemCode.APP_UPDATE_REQUIRED,
          "This server needs app version "
              + minimum.get()
              + " or newer; this app is "
              + version.get()
              + ". Update the app.");
      return;
    }
    chain.doFilter(request, response);
  }
}
