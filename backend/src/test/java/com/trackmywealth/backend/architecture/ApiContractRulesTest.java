package com.trackmywealth.backend.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.trackmywealth.fixtures.contract.DocumentedController;
import com.trackmywealth.fixtures.contract.UndeclaredHeadersController;
import com.trackmywealth.fixtures.contract.UndocumentedController;
import com.trackmywealth.fixtures.contract.UndocumentedPlainController;
import com.trackmywealth.fixtures.contract.ViewController;
import org.junit.jupiter.api.Test;

/**
 * US-29-02 (#372): a new operation without an explicit {@code operationId}, a controller without a
 * tag, a success status or (US-29-09, #455) a response header the contract does not document fails
 * the build - {@link ArchitectureTest}'s contract rules applied to fixture controllers. Only what
 * springdoc documents is held to them: not a view controller, not a {@code @Hidden} handler.
 */
class ApiContractRulesTest {

  private static final JavaClasses UNDOCUMENTED =
      new ClassFileImporter().importClasses(UndocumentedController.class);
  private static final JavaClasses UNDOCUMENTED_PLAIN =
      new ClassFileImporter().importClasses(UndocumentedPlainController.class);
  private static final JavaClasses VIEW =
      new ClassFileImporter().importClasses(ViewController.class);
  private static final JavaClasses DOCUMENTED =
      new ClassFileImporter().importClasses(DocumentedController.class);
  private static final JavaClasses UNDECLARED_HEADERS =
      new ClassFileImporter().importClasses(UndeclaredHeadersController.class);

  @Test
  void aControllerWithoutATagFails() {
    assertThatThrownBy(() -> ArchitectureTest.api_controllers_declare_their_tag.check(UNDOCUMENTED))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("UndocumentedController")
        .hasMessageContaining("@Tag");
  }

  @Test
  void anOperationWithoutAnOperationIdFailsEvenWithASummary() {
    assertThatThrownBy(
            () -> ArchitectureTest.api_operations_declare_their_operation_id.check(UNDOCUMENTED))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("withoutOperation()")
        .hasMessageContaining("withoutOperationId()");
  }

  @Test
  void aHandlerAnswering201Or204WithoutDeclaringItFails() {
    assertThatThrownBy(() -> successStatusRule().check(UNDOCUMENTED))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("createdWithoutStatus() answers [201 CREATED]")
        .hasMessageContaining("deletedWithoutStatus() answers [204 NO_CONTENT]")
        .hasMessageNotContaining("withoutOperation()");
  }

  @Test
  void aStatusSetInALambdaAMethodReferenceOrAHelperOfTheControllerIsSeen() {
    assertThatThrownBy(() -> successStatusRule().check(UNDOCUMENTED))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("createdInLambda() answers [201 CREATED]")
        .hasMessageContaining("createdByReference() answers [201 CREATED]")
        .hasMessageContaining("createdInHelper() answers [201 CREATED]");
  }

  @Test
  void aStatusFromANumberCannotBeReadAndFails() {
    assertThatThrownBy(() -> successStatusRule().check(UNDOCUMENTED))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("statusFromNumber() sets a status this rule cannot read");
  }

  @Test
  void aDeclaredStatusTheHandlerNeverAnswersFails() {
    assertThatThrownBy(() -> successStatusRule().check(UNDOCUMENTED))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("declaresCreatedAnswersOk() answers [200 OK] without declaring it")
        .hasMessageContaining(
            "declaresCreatedAnswersOk() documents [201 CREATED] in @SuccessStatus but never"
                + " answers it");
  }

  @Test
  void aHeaderSentWithoutDeclaringItFailsNamingTheHandler() {
    assertThatThrownBy(() -> headerRule().check(UNDECLARED_HEADERS))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("versionedWithoutHeaders() sends ETAG without declaring it")
        .hasMessageContaining("eTagInHelperWithoutHeaders() sends ETAG without declaring it")
        .hasMessageContaining("locationWithoutHeaders() sends LOCATION without declaring it")
        .hasMessageContaining("locationFromHttpHeaders() sends LOCATION without declaring it");
  }

  @Test
  void aDeclaredHeaderTheHandlerNeverSendsOrOnNoStatusThatCarriesItFails() {
    assertThatThrownBy(() -> headerRule().check(UNDECLARED_HEADERS))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(
            "declaresLocationNeverSendsIt() declares LOCATION in @SendsHeaders but never sends it")
        .hasMessageContaining(
            "locationOnOk() declares LOCATION in @SendsHeaders, but none of its success statuses"
                + " [200 OK] carries it")
        .hasMessageNotContaining("locationOnOk() sends");
  }

  @Test
  void aViewControllerIsNotPartOfTheContract() {
    // Nothing in it is selected: the rules would otherwise fail for matching nothing at all.
    assertThatCode(
            () ->
                ArchitectureTest.api_controllers_declare_their_tag
                    .allowEmptyShould(true)
                    .check(VIEW))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                ArchitectureTest.api_operations_declare_their_operation_id
                    .allowEmptyShould(true)
                    .check(VIEW))
        .doesNotThrowAnyException();
  }

  @Test
  void aPlainControllerWithResponseBodyHandlersIsHeldToTheSameRules() {
    assertThatThrownBy(
            () -> ArchitectureTest.api_controllers_declare_their_tag.check(UNDOCUMENTED_PLAIN))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("UndocumentedPlainController");
    assertThatThrownBy(
            () ->
                ArchitectureTest.api_operations_declare_their_operation_id.check(
                    UNDOCUMENTED_PLAIN))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("plainWithoutOperation()");
  }

  @Test
  void aControllerFollowingTheRulesPasses() {
    assertThatCode(() -> ArchitectureTest.api_controllers_declare_their_tag.check(DOCUMENTED))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> ArchitectureTest.api_operations_declare_their_operation_id.check(DOCUMENTED))
        .doesNotThrowAnyException();
    // Its @Hidden handler answers 202 with neither @Operation nor @SuccessStatus.
    assertThatCode(() -> successStatusRule().check(DOCUMENTED)).doesNotThrowAnyException();
    // Its handlers declare exactly the ETag and Location they send; the others send neither.
    assertThatCode(() -> headerRule().check(DOCUMENTED)).doesNotThrowAnyException();
  }

  private static ArchRule headerRule() {
    return ArchitectureTest.api_operations_send_their_documented_headers;
  }

  private static ArchRule successStatusRule() {
    return ArchitectureTest.api_operations_answer_their_documented_success_statuses;
  }
}
