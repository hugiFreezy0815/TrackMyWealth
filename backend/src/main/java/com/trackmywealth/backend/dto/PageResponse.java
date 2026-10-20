package com.trackmywealth.backend.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * One page of a paged list (#373, ADR 0006): the items and where they sit in the whole list. The
 * shape matches Spring's {@code PagedModel} but is owned by this API, so a framework upgrade never
 * changes it. Pages are zero-based; {@code page.size} is the size actually applied, after the cap.
 */
public record PageResponse<T>(List<T> content, PageMetadata page) {

  public PageResponse {
    content = List.copyOf(content);
  }

  /** The page's items with the position and totals of the query that produced them. */
  public static <T> PageResponse<T> of(Page<T> page) {
    return new PageResponse<>(
        page.getContent(),
        new PageMetadata(
            page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
  }

  /**
   * Where the page sits: its zero-based {@code number}, the {@code size} applied, and the totals
   * over every row the caller may view.
   */
  public record PageMetadata(int number, int size, long totalElements, int totalPages) {}
}
