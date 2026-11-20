package com.trackmywealth.backend.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * US-29-16 (#576), ADR 0006 "Deprecation and removal": an element of {@code /api/v1} that is on its
 * way out. On a handler method the whole operation is deprecated; on a component of a request or
 * response record, that field.
 *
 * <ul>
 *   <li>The contract marks the operation or the field {@code deprecated: true}.
 *   <li>Every response of an operation that is deprecated, or whose request or response body
 *       contains a deprecated field, carries {@code Deprecation} (RFC 9745) with {@link #since()}
 *       and {@code Link: <}{@link #migration()}{@code >; rel="deprecation"}, and {@code Sunset}
 *       (RFC 8594) once {@link #sunset()} is set ({@link DeprecationHeaders}).
 *   <li>Every such call is counted by operation and app version ({@code
 *       tmw.api.deprecated.requests}), so an operator can see which app versions still use it.
 * </ul>
 *
 * <p>Removing the element later is still a declared breaking change ({@code api-breaking}), and
 * only once every supported app version has migrated (the configured minimum app version is at
 * least the first one that no longer uses it) and the sunset has passed. See {@code
 * docs/api/conventions.md}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface ApiDeprecation {

  /** The announced deprecation date, ISO {@code yyyy-MM-dd}. It may lie in the future. */
  String since();

  /** The absolute {@code https} URL of the migration note, for example a conventions anchor. */
  String migration();

  /**
   * The committed removal date, ISO {@code yyyy-MM-dd} and after {@link #since()}; empty until the
   * date can be committed to.
   */
  String sunset() default "";
}
