package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.web.Deprecations.Deprecation;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;

class DeprecationsTest {

  static final String NOTE = "https://example.org/migrate#old";
  static final String OTHER_NOTE = "https://example.org/migrate#other";

  record Plain(String name) {}

  record WithOldField(
      String name,
      @ApiDeprecation(since = "2027-03-01", migration = NOTE, sunset = "2027-09-01")
          String oldName) {}

  record Outer(List<WithOldField> rows) {}

  record OtherOldField(
      @ApiDeprecation(since = "2027-01-15", migration = OTHER_NOTE, sunset = "2027-12-01")
          String code) {}

  record Both(WithOldField first, OtherOldField second) {}

  record Cycle(String name, List<Cycle> children) {}

  record BadDate(@ApiDeprecation(since = "1 March 2027", migration = NOTE) String x) {}

  record BadOrder(
      @ApiDeprecation(since = "2027-03-01", migration = NOTE, sunset = "2027-03-01") String x) {}

  record BadLink(@ApiDeprecation(since = "2027-03-01", migration = "/docs/api") String x) {}

  record PlainLink(
      @ApiDeprecation(since = "2027-03-01", migration = "http://example.org") String x) {}

  record HostlessLink(@ApiDeprecation(since = "2027-03-01", migration = "https:notes") String x) {}

  @SuppressWarnings("unused")
  static class Handlers {

    ResponseEntity<Plain> nothing(@RequestBody Plain body) {
      return null;
    }

    @ApiDeprecation(since = "2027-02-01", migration = NOTE)
    Plain wholeOperation() {
      return null;
    }

    ResponseEntity<PageResponse<WithOldField>> inAPage() {
      return null;
    }

    Outer nested() {
      return null;
    }

    WithOldField[] inAnArray() {
      return null;
    }

    void inTheRequestBody(@RequestBody Outer body) {}

    void inARequestPart(@RequestPart("meta") Outer meta) {}

    void inAQueryParameterOnly(@RequestParam WithOldField notABody) {}

    void nestedInAQueryParameter(String id, List<Outer> notABody) {}

    void plainQueryParameter(@RequestParam Plain notABody) {}

    ResponseEntity<?> wildcardBody() {
      return null;
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity rawBody() {
      return null;
    }

    Object objectBody() {
      return null;
    }

    void objectRequestBody(@RequestBody Object body) {}

    void typedRequestBody(@RequestBody List<Outer> body) {}

    ResponseEntity<Void> noBody() {
      return null;
    }

    @ApiDeprecation(since = "2027-02-01", migration = NOTE)
    Both merged() {
      return null;
    }

    Cycle cycle() {
      return null;
    }

    BadDate badDate() {
      return null;
    }

    BadOrder badOrder() {
      return null;
    }

    BadLink badLink() {
      return null;
    }

    PlainLink plainLink() {
      return null;
    }

    HostlessLink hostlessLink() {
      return null;
    }
  }

  @Test
  void anOperationThatUsesNothingDeprecatedIsNot() {
    assertThat(of("nothing", Plain.class)).isEmpty();
    assertThat(of("cycle")).isEmpty();
    assertThat(of("plainQueryParameter", Plain.class)).isEmpty();
  }

  @Test
  void aDeprecatedOperation() {
    assertThat(of("wholeOperation"))
        .contains(new Deprecation(true, LocalDate.of(2027, 2, 1), null, List.of(URI.create(NOTE))));
  }

  @Test
  void aDeprecatedFieldReachedThroughGenericsListsAndNesting() {
    Deprecation field =
        new Deprecation(
            false, LocalDate.of(2027, 3, 1), LocalDate.of(2027, 9, 1), List.of(URI.create(NOTE)));

    assertThat(of("inAPage")).contains(field);
    assertThat(of("nested")).contains(field);
    assertThat(of("inAnArray")).contains(field);
    assertThat(of("inTheRequestBody", Outer.class)).contains(field);
    assertThat(of("inARequestPart", Outer.class)).contains(field);
  }

  @Test
  void mergesToTheEarliestDatesAndEveryNote() {
    assertThat(of("merged"))
        .contains(
            new Deprecation(
                true,
                LocalDate.of(2027, 1, 15),
                LocalDate.of(2027, 9, 1),
                List.of(URI.create(NOTE), URI.create(OTHER_NOTE))));
  }

  @Test
  void aWrongDeclarationFailsNamingTheElement() {
    assertThatIllegalStateException()
        .isThrownBy(() -> of("badDate"))
        .withMessageContaining("BadDate.x")
        .withMessageContaining("since must be yyyy-MM-dd");
    assertThatIllegalStateException()
        .isThrownBy(() -> of("badOrder"))
        .withMessageContaining("sunset 2027-03-01 is not after since 2027-03-01");
    assertThatIllegalStateException()
        .isThrownBy(() -> of("badLink"))
        .withMessageContaining("absolute https URL");
    assertThatIllegalStateException()
        .isThrownBy(() -> of("plainLink"))
        .withMessageContaining("absolute https URL");
    assertThatIllegalStateException()
        .isThrownBy(() -> of("hostlessLink"))
        .withMessageContaining("absolute https URL");
  }

  @Test
  void aDeprecatedFieldOutsideABodyFailsNamingItAndTheOperation() {
    assertThatIllegalStateException()
        .isThrownBy(() -> of("inAQueryParameterOnly", WithOldField.class))
        .withMessageContaining("@ApiDeprecation on WithOldField.oldName")
        .withMessageContaining("Handlers.inAQueryParameterOnly binds it from parameter 0")
        .withMessageContaining("Deprecate the operation instead");
    assertThatIllegalStateException()
        .isThrownBy(() -> of("nestedInAQueryParameter", String.class, List.class))
        .withMessageContaining("from parameter 1");
  }

  @Test
  void aBodyWhoseTypeCannotBeFollowedFailsTheContract() {
    assertThatIllegalStateException()
        .isThrownBy(() -> requireTraceable("wildcardBody"))
        .withMessageContaining("Handlers.wildcardBody: declare the type of its response body");
    assertThatIllegalStateException().isThrownBy(() -> requireTraceable("rawBody"));
    assertThatIllegalStateException().isThrownBy(() -> requireTraceable("objectBody"));
    assertThatIllegalStateException()
        .isThrownBy(() -> requireTraceable("objectRequestBody", Object.class))
        .withMessageContaining("its request body 0");
  }

  @Test
  void typedBodiesAndNoBodyCanBeFollowed() {
    assertThatNoException().isThrownBy(() -> requireTraceable("typedRequestBody", List.class));
    assertThatNoException().isThrownBy(() -> requireTraceable("noBody"));
    assertThatNoException().isThrownBy(() -> requireTraceable("inAPage"));
    assertThatNoException().isThrownBy(() -> requireTraceable("inAnArray"));
    assertThatNoException()
        .isThrownBy(() -> requireTraceable("inAQueryParameterOnly", WithOldField.class));
  }

  @Test
  void aSingleComponent() throws Exception {
    assertThat(Deprecations.of(WithOldField.class.getRecordComponents()[1]))
        .hasValueSatisfying(annotation -> assertThat(annotation.since()).isEqualTo("2027-03-01"));
    assertThat(Deprecations.of(WithOldField.class.getRecordComponents()[0])).isEmpty();
    assertThatIllegalStateException()
        .isThrownBy(() -> Deprecations.of(BadLink.class.getRecordComponents()[0]));
  }

  private static void requireTraceable(String name, Class<?>... parameters) {
    Deprecations.requireTraceableBodies(method(name, parameters));
  }

  private static Method method(String name, Class<?>... parameters) {
    try {
      return Handlers.class.getDeclaredMethod(name, parameters);
    } catch (NoSuchMethodException missing) {
      throw new AssertionError(missing);
    }
  }

  private static Optional<Deprecation> of(String name, Class<?>... parameters) {
    return Deprecations.of(method(name, parameters));
  }
}
