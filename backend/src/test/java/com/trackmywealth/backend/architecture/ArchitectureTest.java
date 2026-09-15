package com.trackmywealth.backend.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.Architectures.LayeredArchitecture;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.service.AccountService;
import com.trackmywealth.backend.service.InstitutionService;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * Codifies the layering and package conventions from {@code
 * docs/architecture/development-standards.md} as executable rules, not just documentation - see
 * that file for the full rationale and for how this class is expected to grow.
 *
 * <p>Deliberately covers packages (controller, service, repository, entity, dto) that don't exist
 * yet at this stage of the project: ArchUnit rules over an empty package are vacuously true, so
 * these start enforcing automatically the moment the first class lands in one of them, rather than
 * needing to be written retroactively - the same pattern EPIC-28's cross-tenant test suite
 * (US-28-04) uses for entity coverage.
 */
@AnalyzeClasses(
    packagesOf = com.trackmywealth.backend.TrackMyWealthApplication.class,
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule layers_are_respected = buildLayeredArchitecture();

  // allowEmptyShould(true) on each rule below: ArchUnit 1.x fails a rule by default if it
  // matches zero classes (an anti-typo safeguard), which would make every one of these fail
  // right now since none of these packages exist yet. That's the correct default for a mature
  // codebase but not for this one yet - explicitly opting in to "pass vacuously until the
  // package is populated" is what makes these enforce automatically the moment real code lands,
  // per this class's Javadoc, without weakening the rule once classes do exist.

  // FR-API contract (see EPIC-29 in docs/user-stories/BACKLOG-remaining-epics.md): the REST
  // boundary speaks DTOs, never JPA entities directly - erodes easily if not enforced.
  @ArchTest
  static final ArchRule controllers_do_not_expose_entities =
      noClasses()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..entity..")
          .because(
              "REST responses must be DTOs, never JPA entities (FR-API contract, EPIC-29) -"
                  + " depend on a mapper/service-layer DTO instead")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule controllers_are_named_consistently =
      classes()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .haveSimpleNameEndingWith("Controller")
          .andShould()
          .beAnnotatedWith(RestController.class)
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule services_are_named_consistently =
      classes()
          .that()
          .resideInAPackage("..service..")
          .should()
          .haveSimpleNameEndingWith("Service")
          .andShould()
          .beAnnotatedWith(Service.class)
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule repositories_are_named_consistently =
      classes()
          .that()
          .resideInAPackage("..repository..")
          .should()
          .haveSimpleNameEndingWith("Repository")
          .andShould()
          .beAnnotatedWith(Repository.class)
          .allowEmptyShould(true);

  // US-05-04/DM-17/20/21/FR-ACC-010/011/012: account_type may drive behaviour only inside
  // AccountService, which translates it into the capability flags (Account.holdsPositions etc.)
  // - not account_type itself - every other layer (consolidation, allocation, net worth,
  // navigation) must branch on instead. Neither the consolidation nor allocation package exists
  // yet (EPIC 15/17 haven't started), so this rule is checked against the whole codebase rather
  // than scoped to one not-yet-existent package - it already has real classes to check today (every
  // class other than AccountService), so unlike the vacuously-true rules above it doesn't need
  // allowEmptyShould(true), and it will keep covering EPIC 15/17's code the moment it's added,
  // wherever it lands.
  @ArchTest
  static final ArchRule only_account_service_branches_on_account_type =
      noClasses()
          .that()
          .areNotAssignableTo(AccountService.class)
          .should()
          .callMethod(Account.class, "getAccountType")
          .because(
              "account_type must drive behaviour only inside AccountService (US-05-04) - branch on"
                  + " the capability flags it computes instead");

  // US-04-02/FR-INS-008/012, RULE-020/022: institution_type must never restrict or otherwise
  // drive which account_type may be added under a financial_institution - a securities depot
  // under a "Bank" and a cash account under a "Pension Provider" are both exactly as valid as any
  // other combination. Unlike account_type (which legitimately drives behaviour inside
  // AccountService, translated into capability flags), institution_type has no sanctioned
  // exception at all for account creation - InstitutionService reads it only to manage the
  // institution itself (e.g. rendering its own summary), never to gate what account_type a
  // caller may create. Same reasoning as only_account_service_branches_on_account_type for why
  // this doesn't need allowEmptyShould(true): InstitutionService already has real classes to
  // check against today.
  @ArchTest
  static final ArchRule only_institution_service_reads_institution_type =
      noClasses()
          .that()
          .areNotAssignableTo(InstitutionService.class)
          .should()
          .callMethod(FinancialInstitution.class, "getInstitutionType")
          .because(
              "institution_type must never restrict which account_type may be added under it"
                  + " (US-04-02/FR-INS-008/012, RULE-020/022) - AccountService must never read"
                  + " institution_type at all, not even inside the one sanctioned exception"
                  + " account_type itself gets");

  @ArchTest
  static final ArchRule no_standard_streams =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest
  static final ArchRule no_java_util_logging =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  @ArchTest
  static final ArchRule no_generic_exceptions =
      GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  private static LayeredArchitecture buildLayeredArchitecture() {
    return Architectures.layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .withOptionalLayers(true) // see the allowEmptyShould comment above - same reasoning
        .layer("Controller")
        .definedBy("..controller..")
        .layer("Service")
        .definedBy("..service..")
        .layer("Repository")
        .definedBy("..repository..")
        .layer("Entity")
        .definedBy("..entity..")
        .layer("Config")
        .definedBy("..config..")
        .whereLayer("Controller")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("Repository")
        .mayOnlyBeAccessedByLayers("Service")
        .whereLayer("Entity")
        .mayOnlyBeAccessedByLayers("Repository", "Service");
  }
}
