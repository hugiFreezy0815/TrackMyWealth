package com.trackmywealth.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.trackmywealth.backend.architecture.fixture.CallGraphFixtures;
import com.trackmywealth.backend.error.ProblemCode;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * US-29-04 (#374): the call graph behind {@code ProblemCodeCoverageTest} follows every kind of edge
 * a service uses to reach an error - a rule that misses one passes on a missing code.
 */
class ProblemCodeCallGraphTest {

  private static JavaClasses classes;
  private static ProblemCodeCallGraph graph;

  @BeforeAll
  static void importFixtures() {
    classes =
        new ClassFileImporter()
            .importPackages(
                CallGraphFixtures.class.getPackageName(), "com.trackmywealth.backend.error");
    graph = new ProblemCodeCallGraph(classes);
  }

  @Test
  void aCodeInALambdaIsReached() {
    assertThat(reached("inLambda")).containsExactly(ProblemCode.ACCOUNT_ARCHIVED);
  }

  @Test
  void aCodeBehindAMethodReferenceIsReached() {
    assertThat(reached("throughMethodReference")).containsExactly(ProblemCode.IMPORT_BATCH_STATE);
  }

  @Test
  void aCodeInAnImplementationIsReachedThroughItsInterface() {
    assertThat(reached("throughInterface")).containsExactly(ProblemCode.IMPORT_TEMPLATE_READ_ONLY);
  }

  @Test
  void aCodeInAnExceptionConstructorIsReached() {
    assertThat(reached("throughExceptionConstructor"))
        .containsExactly(ProblemCode.RECONCILIATION_FINALIZED);
  }

  @Test
  void aPlainErrorStatusReachesItsClassLevelCode() {
    assertThat(reached("plainStatus")).containsExactly(ProblemCode.UNPROCESSABLE);
    assertThat(reached("transitive")).containsExactly(ProblemCode.UNPROCESSABLE);
  }

  @Test
  void aSuccessStatusReachesNothing() {
    assertThat(reached("successStatus")).isEmpty();
  }

  @Test
  void theRegistrysOwnStatusValidationReachesNothing() {
    // HttpStatus.CONFLICT is passed here, but it is the code's own status, not a plain 409 - the
    // fixture lives outside ..error.., so the access counts; the registry's forStatus must not.
    assertThat(reached("validatedStatus"))
        .containsExactlyInAnyOrder(ProblemCode.VERSION_CONFLICT, ProblemCode.CONFLICT);
  }

  private static Set<ProblemCode> reached(String name) {
    JavaMethod method =
        classes.get(CallGraphFixtures.class).getMethods().stream()
            .filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow();
    return graph.reachableCodes(method);
  }
}
