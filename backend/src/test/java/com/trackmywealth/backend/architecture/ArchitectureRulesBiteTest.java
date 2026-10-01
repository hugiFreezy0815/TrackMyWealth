package com.trackmywealth.backend.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import archfixture.controller.StrayEntityController;
import archfixture.dto.StrayEntityResponse;
import archfixture.model.StrayEntity;
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

  @Test
  void aServiceDependingOnTheWebLayerFailsTheBuild() {
    JavaClasses coupled = new ClassFileImporter().importClasses(WebCoupledService.class);
    assertThatThrownBy(
            () -> ArchitectureTest.services_do_not_depend_on_the_web_layer.check(coupled))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("WebCoupledService");
  }
}
