package com.trackmywealth.backend.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import archfixture.controller.StrayEntityController;
import archfixture.dto.StrayEntityResponse;
import archfixture.model.StrayEntity;
import archfixture.service.CategoryService;
import archfixture.service.WebCoupledService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.trackmywealth.backend.architecture.leak.controller.LeakyController;
import com.trackmywealth.backend.architecture.leak.dto.LeakyResponse;
import com.trackmywealth.backend.architecture.leak.entity.LeakedEntity;
import org.junit.jupiter.api.Test;

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

  static class WorkspaceImpersonatingService {
    void actAs(java.util.UUID workspaceId) {
      com.trackmywealth.backend.security.SystemWorkspaceContext.runInWorkspace(
          workspaceId, () -> {});
    }
  }
}
