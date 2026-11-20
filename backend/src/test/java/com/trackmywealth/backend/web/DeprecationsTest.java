package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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
    assertThat(of("inAQueryParameterOnly", WithOldField.class)).isEmpty();
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
  void aSingleComponent() throws Exception {
    assertThat(Deprecations.of(WithOldField.class.getRecordComponents()[1]))
        .hasValueSatisfying(annotation -> assertThat(annotation.since()).isEqualTo("2027-03-01"));
    assertThat(Deprecations.of(WithOldField.class.getRecordComponents()[0])).isEmpty();
    assertThatIllegalStateException()
        .isThrownBy(() -> Deprecations.of(BadLink.class.getRecordComponents()[0]));
  }

  private static Optional<Deprecation> of(String name, Class<?>... parameters) {
    try {
      Method method = Handlers.class.getDeclaredMethod(name, parameters);
      return Deprecations.of(method);
    } catch (NoSuchMethodException missing) {
      throw new AssertionError(missing);
    }
  }
}
