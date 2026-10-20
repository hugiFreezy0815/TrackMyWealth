package com.trackmywealth.backend.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller's {@link org.springframework.data.domain.Pageable} parameter as a paged list
 * under the one paging contract (#373, ADR 0006). {@link PagedArgumentResolver} reads {@code page}
 * (zero-based), {@code size} (default {@value PagedArgumentResolver#DEFAULT_SIZE}, capped at
 * {@value PagedArgumentResolver#MAX_SIZE}) and any number of {@code sort=<field>[,asc|desc]}, where
 * {@code field} must be one of {@link #sort()}; anything else is a 400 {@code INVALID_SORT}. The
 * order always ends with {@link #tieBreaker()}, so pages never overlap or skip rows.
 *
 * <p>Every {@code Pageable} parameter of a controller carries this annotation ({@code
 * ArchitectureTest.paged_parameters_declare_their_sort_whitelist}).
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Paged {

  /** The fields a client may sort by, named as in the response. */
  SortField[] sort();

  /** The order without a {@code sort} parameter: {@code <field>,<asc|desc>} of {@link #sort()}. */
  String defaultSort();

  /**
   * The entity property that makes the order unique, appended last in the direction of the first
   * sort field unless the order already holds it.
   */
  String tieBreaker() default "id";

  /** A sortable field: its wire name and the entity properties it orders by, in that order. */
  @Documented
  @Target({})
  @Retention(RetentionPolicy.RUNTIME)
  @interface SortField {

    /** The field's name in the response, as a client writes it in {@code sort}. */
    String name();

    /** The entity properties to order by; empty means the property named like the field. */
    String[] properties() default {};
  }
}
