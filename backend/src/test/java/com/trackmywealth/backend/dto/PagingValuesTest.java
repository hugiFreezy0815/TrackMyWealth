package com.trackmywealth.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;

/** PR #388 review: a service refuses a page the paging contract would never have resolved. */
class PagingValuesTest {

  @Test
  void aBoundedPageEndingWithTheTieBreakerPassesUnchanged() {
    Pageable pageable =
        PageRequest.of(
            2, PagingValues.MAX_SIZE, Sort.by(Order.desc("bookingDate"), Order.desc("id")));

    assertThat(PagingValues.requireBounded(pageable, "id")).isSameAs(pageable);
  }

  @Test
  void anUnpagedRequestIsRefused() {
    assertThatThrownBy(() -> PagingValues.requireBounded(Pageable.unpaged(), "id"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be paged");
  }

  @Test
  void aPageAboveTheCapIsRefused() {
    Pageable huge = PageRequest.of(0, 100_000, Sort.by("id"));

    assertThatThrownBy(() -> PagingValues.requireBounded(huge, "id"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most 200");
  }

  @Test
  void aPageWithoutTheTieBreakerLastIsRefused() {
    assertThatThrownBy(() -> PagingValues.requireBounded(PageRequest.of(0, 50), "id"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("with id last");
    Pageable tieBreakerFirst = PageRequest.of(0, 50, Sort.by("id", "bookingDate"));
    assertThatThrownBy(() -> PagingValues.requireBounded(tieBreakerFirst, "id"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
