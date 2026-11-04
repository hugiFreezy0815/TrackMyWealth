package com.trackmywealth.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.web.OperationProblemCodes;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-29-04 (#374): every endpoint's {@code @ProblemCodes} list is true, so the OpenAPI contract
 * names exactly the codes a client must handle.
 *
 * <p>The codes an endpoint can reach are found statically: from the handler method, every call,
 * constructor call, method reference and lambda body inside the application is followed (a call
 * through an interface reaches each implementation), and every use of a {@link ProblemCode}
 * constant on the way counts. So does a plain {@code ResponseStatusException}'s error status
 * outside {@code ..error..}: it answers with that status's class-level code ({@link
 * ProblemCode#forStatus}).
 *
 * <p>Three rules, per endpoint:
 *
 * <ol>
 *   <li>every reachable code is documented - declared in {@code @ProblemCodes} or implied by the
 *       mapping ({@link OperationProblemCodes#implied});
 *   <li>a declared code is reachable, or one of {@link #RAISED_OUTSIDE_THE_CALL_GRAPH};
 *   <li>a declared code is not implied anyway.
 * </ol>
 *
 * <p>The analysis over-approximates only where a flag decides: {@link #REVIEWED_UNREACHABLE} lists
 * those, like {@code IfMatchExceptions} does for If-Match.
 */
class ProblemCodeCoverageTest {

  private static final String APPLICATION = "com.trackmywealth.backend";
  private static final String CONTROLLER_PACKAGE = APPLICATION + ".controller";

  /**
   * Codes no call graph reaches, but an endpoint may still answer with - each declared by hand
   * where it applies. Keep the reason with each.
   */
  static final Map<ProblemCode, String> RAISED_OUTSIDE_THE_CALL_GRAPH =
      Map.of(
          ProblemCode.RETRY,
          "GlobalExceptionHandler: a unique constraint a concurrent request won",
          ProblemCode.IMMUTABLE_FIELD,
          "GlobalExceptionHandler: an immutability trigger (account type, currency)",
          ProblemCode.CONFLICT,
          "GlobalExceptionHandler: any other integrity violation (e.g. a currency guard trigger)",
          ProblemCode.ACCOUNT_IDENTIFIER_IN_USE,
          "GlobalExceptionHandler: the identifier unique index after a race");

  /**
   * Codes the call graph reaches but the endpoint never answers with, because a flag the endpoint
   * passes decides it - reviewed one by one, with the reason. Keep this short: an entry hides a
   * code from the contract.
   */
  static final Map<String, Map<ProblemCode, String>> REVIEWED_UNREACHABLE =
      Map.of(
          "AmortisationScheduleController#listEntries",
          Map.of(
              ProblemCode.ACCOUNT_ARCHIVED,
              "requireLoan(..., forChange = false): an archived loan's schedule stays readable"));

  private static JavaClasses classes;
  private static Map<String, Set<ProblemCode>> reachableByEndpoint;
  private static Map<String, Method> endpoints;

  @BeforeAll
  static void analyse() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(APPLICATION);
    ProblemCodeCallGraph graph = new ProblemCodeCallGraph(classes);
    reachableByEndpoint = new TreeMap<>();
    endpoints = new TreeMap<>();
    for (JavaClass controller : classes) {
      // The /error page renders other requests' errors; it has none of its own.
      if (!controller.getPackageName().equals(CONTROLLER_PACKAGE)
          || !controller.isAnnotatedWith(RestController.class)
          || controller.isAssignableTo(ErrorController.class)) {
        continue;
      }
      for (JavaMethod method : controller.getMethods()) {
        Method reflected = method.reflect();
        if (AnnotatedElementUtils.findMergedAnnotation(reflected, RequestMapping.class) == null) {
          continue;
        }
        String key = controller.getSimpleName() + "#" + method.getName();
        endpoints.put(key, reflected);
        reachableByEndpoint.put(key, graph.reachableCodes(method));
      }
    }
  }

  @Test
  void theAnalysisSeesTheEndpointsAndTheCodesTheyReach() {
    assertThat(endpoints).as("endpoints found").hasSizeGreaterThan(100);
    // Canaries: a code named in a lambda (orElseThrow), one in an exception's constructor and one
    // behind a plain ResponseStatusException must all be found, or the graph misses edges.
    assertThat(reachableByEndpoint.get("ImportBatchController#commit"))
        .contains(ProblemCode.IMPORT_BATCH_STATE, ProblemCode.ACCOUNT_ARCHIVED);
    assertThat(reachableByEndpoint.get("ReconciliationController#accept"))
        .contains(ProblemCode.RECONCILIATION_STALE);
    assertThat(reachableByEndpoint.get("SetupController#bootstrapAdministrator"))
        .contains(ProblemCode.CONFLICT);
  }

  @Test
  void everyReachableCodeIsDocumented() {
    Map<String, Set<ProblemCode>> missing = new TreeMap<>();
    endpoints.forEach(
        (key, method) -> {
          Set<ProblemCode> undocumented = EnumSet.noneOf(ProblemCode.class);
          undocumented.addAll(reachableByEndpoint.get(key));
          undocumented.removeAll(OperationProblemCodes.documented(method));
          undocumented.removeAll(REVIEWED_UNREACHABLE.getOrDefault(key, Map.of()).keySet());
          if (!undocumented.isEmpty()) {
            missing.put(key, undocumented);
          }
        });
    assertThat(missing)
        .as("codes an endpoint can reach but does not list in @ProblemCodes:%n%s", suggest(missing))
        .isEmpty();
  }

  @Test
  void everyDeclaredCodeIsReachableOrRaisedOutsideTheCallGraph() {
    Map<String, Set<ProblemCode>> stale = new TreeMap<>();
    endpoints.forEach(
        (key, method) -> {
          Set<ProblemCode> unreachable = EnumSet.noneOf(ProblemCode.class);
          unreachable.addAll(OperationProblemCodes.declared(method));
          unreachable.removeAll(reachableByEndpoint.get(key));
          unreachable.removeAll(RAISED_OUTSIDE_THE_CALL_GRAPH.keySet());
          if (!unreachable.isEmpty()) {
            stale.put(key, unreachable);
          }
        });
    assertThat(stale).as("@ProblemCodes entries the endpoint can no longer reach").isEmpty();
  }

  @Test
  void everyReviewedUnreachableCodeIsStillReachedAndNotDeclared() {
    REVIEWED_UNREACHABLE.forEach(
        (key, codes) -> {
          assertThat(reachableByEndpoint).as("reviewed endpoint %s exists", key).containsKey(key);
          assertThat(reachableByEndpoint.get(key))
              .as("stale REVIEWED_UNREACHABLE entry for %s - remove it", key)
              .containsAll(codes.keySet());
          assertThat(OperationProblemCodes.declared(endpoints.get(key)))
              .as("%s declares a code REVIEWED_UNREACHABLE hides", key)
              .doesNotContainAnyElementsOf(codes.keySet());
        });
  }

  @Test
  void noDeclaredCodeIsImpliedAnyway() {
    Map<String, Set<ProblemCode>> redundant = new TreeMap<>();
    endpoints.forEach(
        (key, method) -> {
          Set<ProblemCode> twice = EnumSet.noneOf(ProblemCode.class);
          twice.addAll(OperationProblemCodes.declared(method));
          twice.retainAll(OperationProblemCodes.implied(method));
          if (!twice.isEmpty()) {
            redundant.put(key, twice);
          }
        });
    assertThat(redundant).as("@ProblemCodes entries the mapping implies anyway").isEmpty();
  }

  // The annotation each endpoint with missing codes would need, to paste.
  private static String suggest(Map<String, Set<ProblemCode>> missing) {
    StringBuilder text = new StringBuilder();
    missing.forEach(
        (key, codes) -> {
          Set<ProblemCode> needed = EnumSet.noneOf(ProblemCode.class);
          needed.addAll(OperationProblemCodes.declared(endpoints.get(key)));
          needed.addAll(codes);
          text.append(key)
              .append(": @ProblemCodes({")
              .append(
                  needed.stream()
                      .map(code -> "ProblemCode." + code.name())
                      .collect(Collectors.joining(", ")))
              .append("})\n");
        });
    return text.toString();
  }
}
