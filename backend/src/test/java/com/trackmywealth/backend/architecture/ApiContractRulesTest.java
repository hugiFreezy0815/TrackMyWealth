package com.trackmywealth.backend.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.trackmywealth.fixtures.contract.DocumentedController;
import com.trackmywealth.fixtures.contract.UndocumentedController;
import com.trackmywealth.fixtures.contract.UndocumentedPlainController;
import org.junit.jupiter.api.Test;

/**
 * US-29-02 (#372): a new operation without an explicit {@code operationId}, or a controller without
 * a tag, fails the build - {@link ArchitectureTest}'s contract rules applied to fixture
 * controllers.
 */
class ApiContractRulesTest {

  private static final JavaClasses UNDOCUMENTED =
      new ClassFileImporter().importClasses(UndocumentedController.class);
  private static final JavaClasses UNDOCUMENTED_PLAIN =
      new ClassFileImporter().importClasses(UndocumentedPlainController.class);
  private static final JavaClasses DOCUMENTED =
      new ClassFileImporter().importClasses(DocumentedController.class);

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
  }
}
