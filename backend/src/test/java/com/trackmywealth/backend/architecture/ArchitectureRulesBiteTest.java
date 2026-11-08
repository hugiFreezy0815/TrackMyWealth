package com.trackmywealth.backend.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import archfixture.config.ClientCoupledConfig;
import archfixture.controller.PageLeakingController;
import archfixture.controller.StrayEntityController;
import archfixture.controller.UncheckedSortController;
import archfixture.dto.StrayEntityResponse;
import archfixture.model.StrayEntity;
import archfixture.service.CategoryService;
import archfixture.service.ProviderCoupledService;
import archfixture.service.WebCoupledService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.trackmywealth.backend.architecture.leak.controller.LeakyController;
import com.trackmywealth.backend.architecture.leak.dto.LeakyResponse;
import com.trackmywealth.backend.architecture.leak.entity.LeakedEntity;
import com.trackmywealth.backend.client.EcbFxRateProvider;
import com.trackmywealth.fixtures.contract.InvalidSuccessStatusController;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EPIC-29 (#149) DoD: the rules that keep JPA entities off the wire are shown to fail on a
 * deliberate violation - a rule that passes on everything would guard nothing. The fixtures live in
 * test code, which {@link ArchitectureTest} does not import, so they never break the real build.
 */
class ArchitectureRulesBiteTest {

  private static final JavaClasses LEAKS =
      new ClassFileImporter()
          .importClasses(LeakyController.class, LeakyResponse.class, LeakedEntity.class);

  @Test
  void aControllerReturningAnEntityFailsTheBuild() {
    assertThatThrownBy(() -> ArchitectureTest.controllers_do_not_expose_entities.check(LEAKS))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("LeakyController");
  }

  @Test
  void aDtoCarryingAnEntityFailsTheBuild() {
    assertThatThrownBy(() -> ArchitectureTest.dtos_do_not_carry_entities.check(LEAKS))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("LeakyResponse");
  }

  // #197: an @Entity outside the entity package is caught by its annotation.
  private static final JavaClasses STRAY =
      new ClassFileImporter()
          .importClasses(StrayEntityController.class, StrayEntityResponse.class, StrayEntity.class);

  @Test
  void aControllerReturningAnEntityFromAnotherPackageFailsTheBuild() {
    assertThatThrownBy(() -> ArchitectureTest.controllers_do_not_expose_entities.check(STRAY))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("StrayEntityController");
  }

  @Test
  void aDtoCarryingAnEntityFromAnotherPackageFailsTheBuild() {
    assertThatThrownBy(() -> ArchitectureTest.dtos_do_not_carry_entities.check(STRAY))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("StrayEntityResponse");
  }

  // #223: a request path acting as a workspace it chose itself fails the build.
  @Test
  void aRequestPathUsingTheSystemWorkspaceContextFailsTheBuild() {
    JavaClasses impersonating =
        new ClassFileImporter().importClasses(WorkspaceImpersonatingService.class);
    assertThatThrownBy(
            () -> ArchitectureTest.only_background_work_acts_as_a_workspace.check(impersonating))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("WorkspaceImpersonatingService");
  }

  @Test
  void aServiceDependingOnTheWebLayerFailsTheBuild() {
    JavaClasses coupled = new ClassFileImporter().importClasses(WebCoupledService.class);
    assertThatThrownBy(
            () -> ArchitectureTest.services_do_not_depend_on_the_web_layer.check(coupled))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("WebCoupledService");
  }

  // #226: a service tied to one FX provider fails the build.
  @Test
  void aServiceDependingOnAConcreteFxRateProviderFailsTheBuild() {
    JavaClasses coupled =
        new ClassFileImporter()
            .importClasses(ProviderCoupledService.class, EcbFxRateProvider.class);
    assertThatThrownBy(
            () ->
                ArchitectureTest.services_do_not_depend_on_a_concrete_fx_rate_provider.check(
                    coupled))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("ProviderCoupledService");
  }

  // #226: settings code depending on a client would close a config <-> client cycle.
  @Test
  void configDependingOnAClientFailsTheBuild() {
    JavaClasses coupled =
        new ClassFileImporter().importClasses(ClientCoupledConfig.class, EcbFxRateProvider.class);
    assertThatThrownBy(() -> ArchitectureTest.config_does_not_depend_on_clients.check(coupled))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("ClientCoupledConfig");
  }

  // #192: a raw 404 in a service method nobody reviewed fails the build, even in a class that has
  // another, reviewed one - the exemption is per method, never per class.
  @Test
  void anUnreviewedRawNotFoundInAServiceFailsTheBuild() {
    JavaClasses bypass = new ClassFileImporter().importClasses(CategoryService.class);
    assertThatThrownBy(
            () ->
                ArchitectureTest.object_level_services_do_not_construct_raw_not_found.check(bypass))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("CategoryService#findCategory");
  }

  // #373: a controller answering with Spring Data's Page - even wrapped - fails the build.
  @Test
  void aControllerReturningASpringDataPageFailsTheBuild() {
    JavaClasses leaking = new ClassFileImporter().importClasses(PageLeakingController.class);
    assertThatThrownBy(
            () -> ArchitectureTest.controllers_do_not_return_spring_data_pages.check(leaking))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("PageLeakingController.wrapped")
        .hasMessageContaining("PageLeakingController.bare");
  }

  // #373: a Pageable or Sort parameter outside the sort whitelist fails the build.
  @Test
  void aPageableWithoutItsSortWhitelistFailsTheBuild() {
    JavaClasses unchecked = new ClassFileImporter().importClasses(UncheckedSortController.class);
    assertThatThrownBy(
            () -> ArchitectureTest.paged_parameters_declare_their_sort_whitelist.check(unchecked))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("UncheckedSortController.pageable")
        .hasMessageContaining("UncheckedSortController.sort")
        .hasMessageNotContaining("UncheckedSortController.whitelisted");
  }

  // #457: every new guard bites on a deliberately invalid documented handler, including a
  // response factory in another class. A plain return still has Spring's implicit 200.
  private static final JavaClasses INVALID_SUCCESS_STATUSES =
      new ClassFileImporter().importClasses(InvalidSuccessStatusController.class);

  @Test
  void anEmptySuccessStatusDeclarationFailsTheBuild() {
    assertThatThrownBy(
            () ->
                ArchitectureTest.api_operations_answer_their_documented_success_statuses.check(
                    INVALID_SUCCESS_STATUSES))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("emptyStatus() declares an empty @SuccessStatus");
  }

  @Test
  void aNon2xxSuccessStatusDeclarationFailsTheBuild() {
    assertThatThrownBy(
            () ->
                ArchitectureTest.api_operations_answer_their_documented_success_statuses.check(
                    INVALID_SUCCESS_STATUSES))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(
            "nonSuccessStatus() declares non-2xx @SuccessStatus value 400 BAD_REQUEST");
  }

  @Test
  void anUnseenResponseStatusFromAnotherClassFailsTheBuild() {
    assertThatThrownBy(
            () ->
                ArchitectureTest.api_operations_answer_their_documented_success_statuses.check(
                    INVALID_SUCCESS_STATUSES))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("externalStatus() returns a ResponseEntity")
        .hasMessageContaining("externalStatusDeclaredOk() returns a ResponseEntity")
        .hasMessageContaining("add a justified per-handler exemption")
        .hasMessageNotContaining("simpleImplicitOk()");
  }

  @Test
  void aMixedLocalAndExternalResponseStatusFailsTheBuild() {
    assertThatThrownBy(
            () ->
                ArchitectureTest.api_operations_answer_their_documented_success_statuses.check(
                    INVALID_SUCCESS_STATUSES))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(
            "mixedLocalAndExternal(boolean) takes a response or status from another class")
        .hasMessageContaining("ExternalResponses#created");
  }

  // A visible 200 on one branch does not vouch for a builder, a status or a status constant from
  // another class on the other: each of these handlers really answers an undocumented 202.
  @Test
  void aResponseBuilderOrStatusFromAnotherClassFailsTheBuild() {
    assertThatThrownBy(
            () ->
                ArchitectureTest.api_operations_answer_their_documented_success_statuses.check(
                    INVALID_SUCCESS_STATUSES))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(
            "builderFromHelper(boolean) takes a response or status from another class")
        .hasMessageContaining("ExternalResponses#accepted")
        .hasMessageContaining(
            "statusFromHelper(boolean) takes a response or status from another class")
        .hasMessageContaining("ExternalResponses#queued")
        .hasMessageContaining(
            "statusFromField(boolean) takes a response or status from another class")
        .hasMessageContaining("ExternalResponses#QUEUED");
  }

  @Test
  void aReviewedHandlerMayTakeItsStatusFromAnotherClass() {
    ArchRule exempted =
        methods()
            .that()
            .areDeclaredIn(InvalidSuccessStatusController.class)
            .should(
                ArchitectureTest.answerTheSuccessStatusesTheyDocument(
                    Set.of("InvalidSuccessStatusController#builderFromHelper")));

    assertThatThrownBy(() -> exempted.check(INVALID_SUCCESS_STATUSES))
        .isInstanceOf(AssertionError.class)
        .hasMessageNotContaining("builderFromHelper(boolean) takes a response or status")
        .hasMessageContaining("statusFromHelper(boolean) takes a response or status");
  }

  @Test
  void aStaleOrAmbiguousStatusExemptionFailsTheBuild() {
    assertThat(
            ArchitectureTest.unmatchedHandlerExemptions(
                Set.of(
                    "InvalidSuccessStatusController#builderFromHelper",
                    "InvalidSuccessStatusController#removedHandler",
                    "InvalidSuccessStatusController#overloaded"),
                INVALID_SUCCESS_STATUSES))
        .containsExactly(
            "InvalidSuccessStatusController#overloaded is overloaded; rename the handler",
            "InvalidSuccessStatusController#removedHandler is no API operation");
  }

  @Test
  void anImplicitOrOptionalOkIsAllowed() {
    JavaClasses valid = new ClassFileImporter().importClasses(ValidImplicitOkController.class);
    assertThatCode(
            () ->
                ArchitectureTest.api_operations_answer_their_documented_success_statuses.check(
                    valid))
        .doesNotThrowAnyException();
  }

  @RestController
  static class ValidImplicitOkController {
    @Operation(operationId = "plainHttpEntity", summary = "A plain HttpEntity answers 200")
    @GetMapping("/fixture/status/plain-entity")
    public HttpEntity<String> simpleHttpEntity() {
      return new HttpEntity<>("ok");
    }

    @Operation(operationId = "optionalBody", summary = "ResponseEntity.of answers 200 or 404")
    @GetMapping("/fixture/status/optional")
    public ResponseEntity<String> optionalBody(@RequestParam boolean present) {
      return ResponseEntity.of(Optional.of("ok").filter(body -> present));
    }

    @Operation(operationId = "nullableBody", summary = "ResponseEntity.ofNullable answers 200")
    @GetMapping("/fixture/status/nullable")
    public ResponseEntity<String> nullableBody(@RequestParam boolean present) {
      return ResponseEntity.ofNullable(present ? "ok" : null);
    }
  }

  static class WorkspaceImpersonatingService {
    void actAs(java.util.UUID workspaceId) {
      com.trackmywealth.backend.security.SystemWorkspaceContext.runInWorkspace(
          workspaceId, () -> {});
    }
  }
}
