package com.trackmywealth.backend.web;

import com.trackmywealth.backend.web.Deprecations.Deprecation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * US-29-16 (#576): puts the deprecation headers on every response of an operation that an {@link
 * ApiDeprecation} affects ({@link Deprecations}), and counts the call.
 *
 * <ul>
 *   <li>{@code Deprecation: @<epoch seconds>} (RFC 9745), the start of the {@code since} day in
 *       UTC. Sent as soon as the element is marked, also before that date: RFC 9745 allows a future
 *       date, and the contract marks the element deprecated from then on too.
 *   <li>{@code Link: <migration note>; rel="deprecation"; type="text/html"}, one per note.
 *   <li>{@code Sunset: <HTTP-date>} (RFC 8594), the start of the earliest committed removal day in
 *       UTC, once one is committed.
 * </ul>
 *
 * <p>Set before the handler runs, so an error response of the operation carries them too. The
 * counter {@value #METRIC} is tagged with the operation id and the {@code X-App-Version} sent
 * ({@value #NO_VERSION} without one); its version tag values are capped ({@code WebMvcConfig}), as
 * the header comes from the client.
 */
@Component
public class DeprecationHeaders implements HandlerInterceptor {

  /** RFC 9745's response header. */
  public static final String DEPRECATION = "Deprecation";

  /** RFC 8594's response header. */
  public static final String SUNSET = "Sunset";

  /** The counter of calls to operations a deprecation affects. */
  public static final String METRIC = "tmw.api.deprecated.requests";

  public static final String OPERATION_TAG = "operation";
  public static final String APP_VERSION_TAG = "app_version";
  public static final String NO_VERSION = "none";

  // RFC 9110's IMF-fixdate: always two-digit days, always GMT.
  private static final DateTimeFormatter HTTP_DATE =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
          .withZone(ZoneOffset.UTC);

  private final MeterRegistry meterRegistry;
  private final Map<Method, Optional<Deprecation>> byHandler = new ConcurrentHashMap<>();

  public DeprecationHeaders(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (handler instanceof HandlerMethod method) {
      byHandler
          .computeIfAbsent(method.getMethod(), Deprecations::of)
          .ifPresent(
              deprecation -> {
                write(deprecation, response);
                count(method.getMethod(), request);
              });
    }
    return true;
  }

  /** Writes the headers of {@code deprecation} to {@code response}. */
  static void write(Deprecation deprecation, HttpServletResponse response) {
    response.setHeader(DEPRECATION, deprecationValue(deprecation.since()));
    for (URI migration : deprecation.migrations()) {
      response.addHeader(HttpHeaders.LINK, linkValue(migration));
    }
    LocalDate sunset = deprecation.sunset();
    if (sunset != null) {
      response.setHeader(SUNSET, sunsetValue(sunset));
    }
  }

  /** RFC 9745's structured-field date: {@code @} and the epoch seconds of the day's start, UTC. */
  public static String deprecationValue(LocalDate since) {
    return "@" + since.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
  }

  /** RFC 8594's HTTP-date of the day's start, UTC. */
  public static String sunsetValue(LocalDate sunset) {
    return HTTP_DATE.format(sunset.atStartOfDay(ZoneOffset.UTC));
  }

  /** The {@code Link} to a migration note (RFC 9745 section 3). */
  public static String linkValue(URI migration) {
    return "<" + migration + ">; rel=\"deprecation\"; type=\"text/html\"";
  }

  private void count(Method handler, HttpServletRequest request) {
    String version =
        AppVersion.parse(request.getHeader(AppVersion.HEADER))
            .map(AppVersion::toString)
            .orElse(NO_VERSION);
    Counter.builder(METRIC)
        .description("Calls of an API operation that a deprecation affects (#576)")
        .tag(OPERATION_TAG, operationId(handler))
        .tag(APP_VERSION_TAG, version)
        .register(meterRegistry)
        .increment();
  }

  private static String operationId(Method handler) {
    Operation operation = handler.getAnnotation(Operation.class);
    // ArchitectureTest requires an operationId on every API handler.
    return operation != null ? operation.operationId() : handler.getName();
  }
}
