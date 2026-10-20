package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.web.Paged.SortField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class PagedArgumentResolverTest {

  private final PagedArgumentResolver resolver = new PagedArgumentResolver();

  @Test
  void itAnswersOnlyForAPagedPageable() throws NoSuchMethodException {
    assertThat(resolver.supportsParameter(parameter("paged"))).isTrue();
    assertThat(resolver.supportsParameter(parameter("unpaged"))).isFalse();
  }

  @Test
  void withoutParametersItIsTheFirstPageOfTheDefaultSizeInTheDefaultOrder() throws Exception {
    Pageable pageable = resolve(new MockHttpServletRequest());

    assertThat(pageable.getPageNumber()).isZero();
    assertThat(pageable.getPageSize()).isEqualTo(PagingValues.DEFAULT_SIZE);
    assertThat(pageable.getSort()).containsExactly(Order.asc("name"), Order.asc("id"));
  }

  @Test
  void itReadsPageSizeAndSort() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addParameter("page", "1");
    request.addParameter("size", "20");
    request.addParameter("sort", "name,desc");

    Pageable pageable = resolve(request);

    assertThat(pageable.getPageNumber()).isEqualTo(1);
    assertThat(pageable.getPageSize()).isEqualTo(20);
    assertThat(pageable.getOffset()).isEqualTo(20);
    assertThat(pageable.getSort()).containsExactly(Order.desc("name"), Order.desc("id"));
  }

  @Test
  void aLargerSizeIsCappedNotRefused() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addParameter("size", "1000");

    assertThat(resolve(request).getPageSize()).isEqualTo(PagingValues.MAX_SIZE);
  }

  @ParameterizedTest
  @CsvSource({"page,-1", "page,abc", "page,1.5", "size,0", "size,-3", "size,ten"})
  void aPageOrSizeThatIsNoValidWholeNumberIsRefused(String name, String value) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addParameter(name, value);

    assertThatThrownBy(() -> resolve(request))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
              assertThat(e.getReason()).startsWith(name + " must be a whole number");
            });
  }

  // PR #388 review: a value sent twice is refused like a malformed one, never resolved to a guess.
  @ParameterizedTest
  @CsvSource({"page,0,3", "size,20,50"})
  void aPageOrSizeSentTwiceIsRefused(String name, String first, String second) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addParameter(name, first, second);

    assertThatThrownBy(() -> resolve(request))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
              assertThat(e.getReason()).isEqualTo(name + " must be sent at most once.");
            });
  }

  // PR #388 review: as with Spring Data's resolver, a blank value counts as not sent.
  @Test
  void aBlankPageOrSizeCountsAsNotSent() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addParameter("page", "");
    request.addParameter("size", " ");

    Pageable pageable = resolve(request);

    assertThat(pageable.getPageNumber()).isZero();
    assertThat(pageable.getPageSize()).isEqualTo(PagingValues.DEFAULT_SIZE);
  }

  @Test
  void anUnknownSortFieldIsRefused() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addParameter("sort", "unknownField,asc");

    assertThatThrownBy(() -> resolve(request))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.INVALID_SORT));
  }

  private Pageable resolve(MockHttpServletRequest request) throws NoSuchMethodException {
    return resolver.resolveArgument(parameter("paged"), null, new ServletWebRequest(request), null);
  }

  private static MethodParameter parameter(String method) throws NoSuchMethodException {
    return new MethodParameter(Declarations.class.getDeclaredMethod(method, Pageable.class), 0);
  }

  @SuppressWarnings("unused")
  static final class Declarations {

    void paged(@Paged(sort = @SortField(name = "name"), defaultSort = "name,asc") Pageable p) {}

    void unpaged(Pageable p) {}
  }
}
