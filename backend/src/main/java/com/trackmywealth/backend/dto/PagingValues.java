package com.trackmywealth.backend.dto;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * The paging contract's limits (#373, ADR 0006), shared by the web layer that reads a request's
 * paging and the services that run the query. {@code web.PagedArgumentResolver} applies them to
 * every client request; {@link #requireBounded} makes a service refuse any other caller's {@code
 * Pageable} that breaks them, such as a future job or export asking for an unbounded page.
 *
 * <p>It lives in {@code dto}, beside the other {@code *Values} classes, because services must not
 * depend on the web layer ({@code ArchitectureTest.services_do_not_depend_on_the_web_layer}).
 */
public final class PagingValues {

  /** The page size without a {@code size} parameter. */
  public static final int DEFAULT_SIZE = 50;

  /** The largest page size; a larger requested {@code size} is capped to it. */
  public static final int MAX_SIZE = 200;

  private PagingValues() {}

  /**
   * {@code pageable} itself, when it is paged, at most {@value #MAX_SIZE} rows long and ordered
   * with {@code tieBreaker} last, so its pages can neither overlap nor skip rows.
   *
   * @throws IllegalArgumentException for any other {@code Pageable} - a programming error, since
   *     every request's paging is resolved under these rules before it reaches a service
   */
  public static Pageable requireBounded(Pageable pageable, String tieBreaker) {
    requirePagedWithinCap(pageable);
    List<Sort.Order> orders = pageable.getSort().toList();
    if (orders.isEmpty() || !tieBreaker.equals(orders.get(orders.size() - 1).getProperty())) {
      throw new IllegalArgumentException(
          "A page must be ordered with " + tieBreaker + " last: " + pageable.getSort());
    }
    return pageable;
  }

  /**
   * {@code pageable} itself, for a list whose query fixes its whole order (a {@code
   * web.Paged#fixedOrder()} list): paged, at most {@value #MAX_SIZE} rows long and unsorted, so
   * Spring Data appends nothing to the query's own {@code ORDER BY}, which must end with a unique
   * tie-breaker.
   *
   * @throws IllegalArgumentException for any other {@code Pageable}, as {@link #requireBounded}
   */
  public static Pageable requireBoundedFixedOrder(Pageable pageable) {
    requirePagedWithinCap(pageable);
    if (pageable.getSort().isSorted()) {
      throw new IllegalArgumentException(
          "A fixed-order page must not carry a sort: " + pageable.getSort());
    }
    return pageable;
  }

  private static void requirePagedWithinCap(Pageable pageable) {
    if (pageable.isUnpaged() || pageable.getPageSize() > MAX_SIZE) {
      throw new IllegalArgumentException(
          "A page must be paged and at most " + MAX_SIZE + " rows long: " + pageable);
    }
  }
}
