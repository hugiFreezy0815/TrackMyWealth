package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.web.Paged.SortField;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort.Order;
import org.springframework.http.HttpStatus;

class SortWhitelistTest {

  private static final SortWhitelist LEDGER = SortWhitelist.of(paged("ledger"));

  @Test
  void withoutASortTheDefaultOrderEndsWithTheTieBreaker() {
    assertThat(LEDGER.resolve(List.of()))
        .containsExactly(Order.desc("bookingDate"), Order.desc("createdAt"), Order.desc("id"));
    assertThat(LEDGER.resolve(null)).isEqualTo(LEDGER.resolve(List.of()));
  }

  @Test
  void aWhitelistedFieldOrdersByItsPropertiesThenTheTieBreakerInTheFirstDirection() {
    assertThat(LEDGER.resolve(List.of("amount,asc")))
        .containsExactly(Order.asc("amount"), Order.asc("id"));
    assertThat(LEDGER.resolve(List.of("amount", "bookingDate,desc")))
        .containsExactly(
            Order.asc("amount"),
            Order.desc("bookingDate"),
            Order.desc("createdAt"),
            Order.asc("id"));
  }

  @Test
  void theDirectionIsAscendingWhenLeftOut() {
    assertThat(LEDGER.resolve(List.of("amount")))
        .containsExactly(Order.asc("amount"), Order.asc("id"));
  }

  // PR #388 review: the server accepts exactly the values the OpenAPI enum lists, no more.
  @Test
  void onlyTheDocumentedSpellingIsAccepted() {
    assertInvalid(List.of("amount,DESC"), "'amount,DESC'", "asc|desc");
    assertInvalid(List.of("amount, desc"), "'amount, desc'", "asc|desc");
    assertInvalid(List.of(" amount"), "' amount'", "bookingDate, amount");
  }

  @Test
  void aTieBreakerAlreadyInTheOrderIsNotRepeated() {
    SortWhitelist rows = SortWhitelist.of(paged("rows"));
    assertThat(rows.resolve(List.of("rowNumber,desc"))).containsExactly(Order.desc("rowNumber"));
  }

  @Test
  void anUnknownFieldIsRefusedNamingTheAllowedOnes() {
    assertInvalid(List.of("unknownField,asc"), "'unknownField'", "bookingDate, amount");
    // An entity property that is not on the whitelist is just as unknown.
    assertInvalid(List.of("createdAt"), "'createdAt'", "bookingDate, amount");
  }

  @Test
  void aMalformedValueIsRefusedNotIgnored() {
    assertInvalid(List.of("amount,sideways"), "'amount,sideways'", "asc|desc");
    assertInvalid(List.of("amount,asc,extra"), "'amount,asc,extra'", "asc|desc");
    assertInvalid(List.of(""), "''", "bookingDate, amount");
  }

  @Test
  void aFieldSortedTwiceIsRefused() {
    assertInvalid(List.of("amount", "amount,desc"), "'amount' only once", "");
  }

  @Test
  void aLongValueIsCutShortInTheDetail() {
    String longField = "x".repeat(500);
    assertThatThrownBy(() -> LEDGER.resolve(List.of(longField)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getReason()).hasSizeLessThan(200).contains("..."));
  }

  @Test
  void itListsItsFieldsAndDefaultForTheContract() {
    assertThat(LEDGER.fieldNames()).containsExactly("bookingDate", "amount");
    assertThat(LEDGER.defaultSort()).isEqualTo("bookingDate,desc");
  }

  @Test
  void aSelfContradictingDeclarationIsAProgrammingError() {
    assertThatThrownBy(() -> SortWhitelist.of(paged("defaultOutsideTheList")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("default sort");
    assertThatThrownBy(() -> SortWhitelist.of(paged("duplicateField")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unique");
    assertThatThrownBy(() -> SortWhitelist.of(paged("noFields")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least one");
  }

  private static void assertInvalid(List<String> sort, String names, String allowed) {
    assertThatThrownBy(() -> LEDGER.resolve(sort))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.INVALID_SORT);
              assertThat(e.getReason()).contains(names).contains(allowed);
            });
  }

  static Paged paged(String method) {
    try {
      return Declarations.class
          .getDeclaredMethod(method, Object.class)
          .getParameters()[0]
          .getAnnotation(Paged.class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unused")
  static final class Declarations {

    void ledger(
        @Paged(
                sort = {
                  @SortField(
                      name = "bookingDate",
                      properties = {"bookingDate", "createdAt"}),
                  @SortField(name = "amount")
                },
                defaultSort = "bookingDate,desc")
            Object pageable) {}

    void rows(
        @Paged(
                sort = @SortField(name = "rowNumber"),
                defaultSort = "rowNumber,asc",
                tieBreaker = "rowNumber")
            Object pageable) {}

    void defaultOutsideTheList(
        @Paged(sort = @SortField(name = "name"), defaultSort = "createdAt,desc") Object pageable) {}

    void duplicateField(
        @Paged(
                sort = {@SortField(name = "name"), @SortField(name = "name")},
                defaultSort = "name,asc")
            Object pageable) {}

    void noFields(
        @Paged(
                sort = {},
                defaultSort = "name,asc")
            Object pageable) {}
  }
}
