package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.Paged.SortField;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.method.HandlerMethod;

/** #373: the paging parameters springdoc documents for a {@link Paged} list. */
class ApiDocumentationPagingTest {

  private final ApiDocumentationConfig config = new ApiDocumentationConfig();

  @Test
  void aPagedListWithoutOtherParametersDocumentsPageSizeAndSort() throws NoSuchMethodException {
    Operation operation = config.pagedListParameters().customize(new Operation(), handler("paged"));

    assertThat(operation.getParameters())
        .extracting(Parameter::getName)
        .containsExactly("page", "size", "sort");
    assertThat(operation.getResponses()).containsKey("400");
  }

  @Test
  void springdocsGenericPageableParameterIsReplaced() throws NoSuchMethodException {
    Operation operation = new Operation().addParametersItem(new Parameter().name("pageable"));

    config.pagedListParameters().customize(operation, handler("paged"));

    assertThat(operation.getParameters())
        .extracting(Parameter::getName)
        .containsExactly("page", "size", "sort");
  }

  @Test
  void anOperationWithoutPagingIsLeftAlone() throws NoSuchMethodException {
    Operation operation =
        config.pagedListParameters().customize(new Operation(), handler("unpaged"));

    assertThat(operation.getParameters()).isNull();
    assertThat(operation.getResponses()).isNull();
  }

  private static HandlerMethod handler(String name) throws NoSuchMethodException {
    return new HandlerMethod(
        new Endpoints(), Endpoints.class.getDeclaredMethod(name, Pageable.class));
  }

  @SuppressWarnings("unused")
  static final class Endpoints {

    void paged(@Paged(sort = @SortField(name = "name"), defaultSort = "name,asc") Pageable p) {}

    void unpaged(Pageable p) {}
  }
}
