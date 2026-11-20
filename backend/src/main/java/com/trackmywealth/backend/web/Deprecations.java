package com.trackmywealth.backend.web;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;

/**
 * US-29-16 (#576): which {@link ApiDeprecation}s affect an operation - its own, and those of every
 * record component reachable from its request and response bodies (through generics such as {@code
 * ResponseEntity<PageResponse<T>>}, lists and nested records) - merged into the one set of headers
 * its responses carry. Only a field of a body can be deprecated: a deprecated component reached
 * through any other parameter fails, and so does a body whose type cannot be followed ({@link
 * #requireTraceableBodies}), so a deprecation is never silently left out.
 */
public final class Deprecations {

  private static final String ON = "@ApiDeprecation on ";

  private Deprecations() {}

  /**
   * The merged deprecation of an operation.
   *
   * @param operation whether the operation itself is deprecated, not only some of its fields
   * @param since the earliest deprecation date among the affected elements
   * @param sunset the earliest committed removal date among them, if any is committed
   * @param migrations the migration notes, each once, in the order found
   */
  public record Deprecation(
      boolean operation, LocalDate since, @Nullable LocalDate sunset, List<URI> migrations) {

    public Deprecation {
      migrations = List.copyOf(migrations);
    }
  }

  /** The deprecation affecting {@code handler}, or empty when nothing it uses is deprecated. */
  public static Optional<Deprecation> of(Method handler) {
    List<Parsed> found = new ArrayList<>();
    ApiDeprecation own = handler.getAnnotation(ApiDeprecation.class);
    if (own != null) {
      found.add(parse(own, where(handler)));
    }
    Set<String> seen = new HashSet<>();
    collect(ResolvableType.forMethodReturnType(handler), found, seen);
    for (int i = 0; i < handler.getParameterCount(); i++) {
      MethodParameter parameter = new MethodParameter(handler, i);
      if (isBody(parameter)) {
        collect(ResolvableType.forMethodParameter(parameter), found, seen);
      } else {
        rejectOutsideABody(handler, parameter);
      }
    }
    if (found.isEmpty()) {
      return Optional.empty();
    }
    LocalDate since =
        found.stream().map(Parsed::since).min(Comparator.naturalOrder()).orElseThrow();
    LocalDate sunset =
        found.stream()
            .map(Parsed::sunset)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);
    Set<URI> migrations = new LinkedHashSet<>();
    found.forEach(parsed -> migrations.add(parsed.migration()));
    return Optional.of(new Deprecation(own != null, since, sunset, List.copyOf(migrations)));
  }

  /**
   * Fails when {@code handler} declares a request or response body whose type cannot be followed
   * (raw, a wildcard or {@code Object}): a deprecated field in it would be neither marked in the
   * contract nor announced in the headers. The contract's generation calls it for every API
   * operation; at run time it is not checked, as framework handlers (the error page, the actuator)
   * are no API operations.
   */
  public static void requireTraceableBodies(Method handler) {
    requireTraceable(handler, ResolvableType.forMethodReturnType(handler), "its response body");
    for (int i = 0; i < handler.getParameterCount(); i++) {
      MethodParameter parameter = new MethodParameter(handler, i);
      if (isBody(parameter)) {
        requireTraceable(
            handler, ResolvableType.forMethodParameter(parameter), "its request body " + i);
      }
    }
  }

  private static void requireTraceable(Method handler, ResolvableType type, String what) {
    if (type.hasUnresolvableGenerics() || type.toClass() == Object.class) {
      throw new IllegalStateException(
          where(handler)
              + ": declare the type of "
              + what
              + " (not raw, ? or Object), or an @ApiDeprecation in it cannot be found (#576)");
    }
  }

  private static boolean isBody(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(RequestBody.class)
        || parameter.hasParameterAnnotation(RequestPart.class);
  }

  /**
   * Only a field of a request or response body can be deprecated: a query or form parameter bound
   * from a record is not a property in the contract, so it could not be marked there.
   */
  private static void rejectOutsideABody(Method handler, MethodParameter parameter) {
    List<Parsed> outside = new ArrayList<>();
    collect(ResolvableType.forMethodParameter(parameter), outside, new HashSet<>());
    if (!outside.isEmpty()) {
      throw new IllegalStateException(
          ON
              + outside.get(0).where()
              + ": "
              + where(handler)
              + " binds it from parameter "
              + parameter.getParameterIndex()
              + ", not from a request body, where the contract cannot mark it. Deprecate the"
              + " operation instead.");
    }
  }

  private static String where(Method handler) {
    return handler.getDeclaringClass().getSimpleName() + "." + handler.getName();
  }

  /** The deprecation of one record component, or empty when it is not deprecated. */
  public static Optional<ApiDeprecation> of(RecordComponent component) {
    ApiDeprecation annotation = component.getAnnotation(ApiDeprecation.class);
    if (annotation != null) {
      parse(annotation, component.getDeclaringRecord().getSimpleName() + "." + component.getName());
    }
    return Optional.ofNullable(annotation);
  }

  private static void collect(ResolvableType type, List<Parsed> found, Set<String> seen) {
    // NONE (an unresolvable type) has no generics and is no record, so it ends below.
    if (!seen.add(type.toString())) {
      return;
    }
    if (type.isArray()) {
      collect(type.getComponentType(), found, seen);
    }
    for (ResolvableType generic : type.getGenerics()) {
      collect(generic, found, seen);
    }
    // Object for a type that cannot be resolved.
    Class<?> raw = type.toClass();
    if (!raw.isRecord()) {
      return;
    }
    for (RecordComponent component : raw.getRecordComponents()) {
      ApiDeprecation annotation = component.getAnnotation(ApiDeprecation.class);
      if (annotation != null) {
        found.add(parse(annotation, raw.getSimpleName() + "." + component.getName()));
      }
      // The component's private field resolves the record's type variables against `type`, e.g.
      // the T of PageResponse<T>.content.
      collect(ResolvableType.forField(fieldOf(raw, component), type), found, seen);
    }
  }

  // Every record component has a private field of its name (JLS 8.10.3).
  private static Field fieldOf(Class<?> record, RecordComponent component) {
    try {
      return record.getDeclaredField(component.getName());
    } catch (NoSuchFieldException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Parsed(LocalDate since, @Nullable LocalDate sunset, URI migration, String where) {}

  /**
   * Validates the annotation; a wrong one fails the contract's generation, naming {@code where}.
   */
  private static Parsed parse(ApiDeprecation annotation, String where) {
    LocalDate since = date(annotation.since(), "since", where);
    LocalDate sunset =
        annotation.sunset().isBlank() ? null : date(annotation.sunset(), "sunset", where);
    if (sunset != null && !sunset.isAfter(since)) {
      throw new IllegalStateException(
          ON + where + ": sunset " + sunset + " is not after since " + since);
    }
    URI migration;
    try {
      migration = URI.create(annotation.migration());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(ON + where + ": migration is not a URL", malformed);
    }
    if (!"https".equals(migration.getScheme()) || migration.getHost() == null) {
      throw new IllegalStateException(
          ON
              + where
              + ": migration must be an absolute https URL of the migration note, was "
              + annotation.migration());
    }
    return new Parsed(since, sunset, migration, where);
  }

  private static LocalDate date(String value, String name, String where) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException malformed) {
      throw new IllegalStateException(
          ON + where + ": " + name + " must be yyyy-MM-dd, was " + value, malformed);
    }
  }
}
