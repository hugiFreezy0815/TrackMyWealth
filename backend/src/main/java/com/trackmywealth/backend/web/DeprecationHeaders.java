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
import java.util.Set;
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
 * ({@value #NO_VERSION} without one). The header comes from the client, so at most {@value
 * #MAX_COUNTED_APP_VERSIONS} versions are kept apart and any further one is counted as {@value
 * #OTHER_VERSION}: the totals stay complete, and a non-zero {@value #OTHER_VERSION} says that
 * callers the counter cannot tell apart still use the element.
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
  public static final String OTHER_VERSION = "other";

  /**
   * How many app versions the counter keeps apart. The version comes from the client, so without a
   * cap a caller could grow the meter registry without bound.
   */
  static final int MAX_COUNTED_APP_VERSIONS = 100;

  // RFC 9110's IMF-fixdate: always two-digit days, always GMT.
  private static final DateTimeFormatter HTTP_DATE =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
          .withZone(ZoneOffset.UTC);

  private final MeterRegistry meterRegistry;
  private final Map<Method, Optional<Deprecation>> byHandler = new ConcurrentHashMap<>();
  private final Set<String> countedVersions = ConcurrentHashMap.newKeySet();

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
    // Stripped like MinimumAppVersionFilter does, which has already refused a malformed one.
    String version =
        Optional.ofNullable(request.getHeader(AppVersion.HEADER))
            .map(String::strip)
            .flatMap(AppVersion::parse)
            .map(AppVersion::toString)
            .orElse(NO_VERSION);
    Counter.builder(METRIC)
        .description("Calls of an API operation that a deprecation affects (#576)")
        .tag(OPERATION_TAG, operationId(handler))
        .tag(APP_VERSION_TAG, countedVersion(version))
        .register(meterRegistry)
        .increment();
  }

  /**
   * {@code version}, or {@value #OTHER_VERSION} once {@value #MAX_COUNTED_APP_VERSIONS} others are
   * kept apart. Two first calls racing for the last place may both get it; the cap is a bound, not
   * an exact count.
   */
  private String countedVersion(String version) {
    if (countedVersions.contains(version)) {
      return version;
    }
    if (countedVersions.size() >= MAX_COUNTED_APP_VERSIONS) {
      return OTHER_VERSION;
    }
    countedVersions.add(version);
    return version;
  }

  private static String operationId(Method handler) {
    Operation operation = handler.getAnnotation(Operation.class);
    // ArchitectureTest requires an operationId on every API handler.
    return operation != null ? operation.operationId() : handler.getName();
  }
}
