package com.trackmywealth.backend.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.web.ProblemDetails;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** US-29-04 (#374): the problem-code registry is complete and consistent. */
class ProblemCodeTest {

  @ParameterizedTest
  @EnumSource(ProblemCode.class)
  void everyCodeHasAnEnglishAndAGermanText(ProblemCode code) {
    String english = Messages.EN.getProperty(code.messageKey());
    String german = Messages.DE.getProperty(code.messageKey());
    assertThat(english).as("EN %s", code.messageKey()).isNotBlank();
    assertThat(german).as("DE %s", code.messageKey()).isNotBlank().isNotEqualTo(english);
  }

  @Test
  void noBundleHoldsATextForAnUnknownCode() {
    for (var bundle : new java.util.Properties[] {Messages.EN, Messages.DE}) {
      Set<String> orphans = new TreeSet<>();
      for (String key : bundle.stringPropertyNames()) {
        if (key.startsWith(ProblemCode.MESSAGE_KEY_PREFIX)
            && !ProblemCode.isRegistered(key.substring(ProblemCode.MESSAGE_KEY_PREFIX.length()))) {
          orphans.add(key);
        }
      }
      assertThat(orphans).as("tmw.error.* keys without a ProblemCode").isEmpty();
    }
  }

  @ParameterizedTest
  @EnumSource(ProblemCode.class)
  void everyCodeHasAnErrorStatusAMeaningAndAReaction(ProblemCode code) {
    assertThat(code.statuses()).isNotEmpty().contains(code.status());
    assertThat(code.statuses()).allMatch(HttpStatus::isError);
    assertThat(code.allows(code.status())).isTrue();
    assertThat(code.meaning()).isNotBlank().endsWith(".");
    assertThat(code.reaction()).isNotNull();
    assertThat(ProblemCode.isRegistered(code.name())).isTrue();
  }

  @Test
  void everyErrorStatusHasARegisteredClassLevelCodeThatAllowsIt() {
    for (HttpStatus status : HttpStatus.values()) {
      if (status.isError()) {
        assertThat(ProblemCode.forStatus(status).allows(status)).as("%s", status).isTrue();
      }
    }
    assertThat(ProblemCode.forStatus(HttpStatus.METHOD_NOT_ALLOWED))
        .isEqualTo(ProblemCode.VALIDATION_FAILED);
    assertThat(ProblemCode.forStatus(HttpStatus.BAD_GATEWAY)).isEqualTo(ProblemCode.INTERNAL);
    assertThat(ProblemCode.forStatus(HttpStatus.PRECONDITION_FAILED))
        .isEqualTo(ProblemCode.VERSION_CONFLICT);
  }

  @Test
  void onlyTheListedCodesTakeMoreThanOneStatus() {
    Set<ProblemCode> multiStatus = EnumSet.noneOf(ProblemCode.class);
    Arrays.stream(ProblemCode.values())
        .filter(code -> code.statuses().size() > 1)
        .forEach(multiStatus::add);
    assertThat(multiStatus).containsExactly(ProblemCode.VERSION_CONFLICT);
    assertThat(ProblemCode.VERSION_CONFLICT.statuses())
        .containsExactlyInAnyOrder(HttpStatus.PRECONDITION_FAILED, HttpStatus.CONFLICT);
  }

  @Test
  void anUnknownNameIsNotRegistered() {
    assertThat(ProblemCode.isRegistered("NOT_A_CODE")).isFalse();
    assertThat(ProblemCode.isRegistered("account_archived")).isFalse();
    assertThat(ProblemCode.isRegistered(null)).isFalse();
  }

  @Test
  void anApiExceptionTakesItsStatusFromTheCodeAndCarriesItsName() {
    ApiException archived = new ApiException(ProblemCode.ACCOUNT_ARCHIVED, "Archived.");

    assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(archived.getCode()).isEqualTo(ProblemCode.ACCOUNT_ARCHIVED);
    assertThat(archived.getBody().getProperties())
        .containsEntry(ProblemCode.PROPERTY, "ACCOUNT_ARCHIVED");
  }

  @Test
  void anApiExceptionTakesAnotherListedStatus() {
    ApiException race =
        new ApiException(HttpStatus.CONFLICT, ProblemCode.VERSION_CONFLICT, "Changed meanwhile.");

    assertThat(race.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(new ApiException(ProblemCode.VERSION_CONFLICT, "Stale.").getStatusCode())
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);
  }

  @Test
  void aStatusTheCodeDoesNotListIsRefused() {
    assertThatThrownBy(
            () ->
                new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, ProblemCode.ACCOUNT_ARCHIVED, "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ACCOUNT_ARCHIVED")
        .hasMessageContaining("422");
    assertThatThrownBy(
            () ->
                ProblemDetails.of(
                    HttpStatusCode.valueOf(404), ProblemCode.IMPORT_FILE_EMPTY, "Not found."))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aClassLevelCodeTakesEveryStatusItStandsFor() {
    // The /error page answers a container's 503 or 413 with forStatus(status): never refused.
    assertThat(
            ProblemDetails.of(HttpStatus.SERVICE_UNAVAILABLE, ProblemCode.INTERNAL, "x")
                .getStatus())
        .isEqualTo(503);
    assertThat(
            ProblemDetails.of(HttpStatus.CONTENT_TOO_LARGE, ProblemCode.VALIDATION_FAILED, "x")
                .getStatus())
        .isEqualTo(413);
  }

  @Test
  void aClassLevelCodeNeverStandsForASuccessOrARedirect() {
    assertThat(ProblemCode.INTERNAL.allows(HttpStatus.OK)).isFalse();
    assertThat(ProblemCode.INTERNAL.allows(HttpStatus.FOUND)).isFalse();
    assertThat(ProblemCode.INTERNAL.allows(HttpStatus.SERVICE_UNAVAILABLE)).isTrue();
  }

  @Test
  void aRetiredNameIsNeverACurrentCode() {
    for (String retired : ProblemCode.RETIRED) {
      assertThat(ProblemCode.isRegistered(retired))
          .as("retired %s is a code again", retired)
          .isFalse();
    }
    assertThat(ProblemCode.RETIRED).doesNotHaveDuplicates();
  }

  @Test
  void deprecationIsReadFromTheConstantsAnnotation() {
    assertThat(Codes.isDeprecated(Lifecycle.OLD)).isTrue();
    assertThat(Codes.isDeprecated(Lifecycle.CURRENT)).isFalse();
    assertThat(Arrays.stream(ProblemCode.values()).filter(ProblemCode::isDeprecated))
        .as("no code is deprecated yet; update this test when one is")
        .isEmpty();
  }

  enum Lifecycle {
    CURRENT,
    @Deprecated
    OLD
  }
}
