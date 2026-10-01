package com.trackmywealth.backend.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.Architectures.LayeredArchitecture;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.service.AccountService;
import com.trackmywealth.backend.service.InstitutionService;
import jakarta.persistence.Entity;
import java.util.Set;
import org.springframework.http.HttpStatus;
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

  // A JPA entity: anything in the entity package, and - #197 - anything annotated @Entity
  // wherever it lives, so an entity declared outside that package cannot slip past the rules.
  static final DescribedPredicate<JavaClass> JPA_ENTITIES =
      JavaClass.Predicates.resideInAPackage("..entity..")
          .or(CanBeAnnotated.Predicates.annotatedWith(Entity.class))
          .as("JPA entities");

  // FR-API contract (see EPIC-29 in docs/user-stories/BACKLOG-remaining-epics.md): the REST
  // boundary speaks DTOs, never JPA entities directly - erodes easily if not enforced.
  @ArchTest
  static final ArchRule controllers_do_not_expose_entities =
      noClasses()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .dependOnClassesThat(JPA_ENTITIES)
          .because(
              "REST responses must be DTOs, never JPA entities (FR-API contract, EPIC-29) -"
                  + " depend on a mapper/service-layer DTO instead")
          .allowEmptyShould(true);

  // EPIC-29 (#149): the controller rule alone lets an entity slip through inside a DTO (a record
  // component, a nested list); a DTO must not reach the entity package either.
  @ArchTest
  static final ArchRule dtos_do_not_carry_entities =
      noClasses()
          .that()
          .resideInAPackage("..dto..")
          .should()
          .dependOnClassesThat(JPA_ENTITIES)
          .because(
              "the API speaks DTOs only (FR-API contract, EPIC-29) - a DTO holding an entity puts"
                  + " the entity on the wire all the same")
          .allowEmptyShould(true);

  // #197: business rules throw ApiException/ResponseStatusException (..error.., spring-web) but
  // never depend on the application's web layer - its filters, handlers and response writers.
  @ArchTest
  static final ArchRule services_do_not_depend_on_the_web_layer =
      noClasses()
          .that()
          .resideInAPackage("..service..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.trackmywealth.backend.web..")
          .because(
              "error types a service throws live in ..error.., so the web layer can change"
                  + " without touching business logic (#197)")
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

  // US-28-02 / #192: a raw service-layer 404 is an easy way to bypass authorization_denial_log.
  // Every service method that still constructs one is listed here by name, with the reason it is
  // not an object-level denial; any other method - including a new one in a listed class - fails
  // the build. Adding an entry is a security-review event, not a convenience.
  static final Set<String> REVIEWED_RAW_NOT_FOUND_METHODS =
      Set.of(
          // The audited denial itself.
          "AuthorizationDenialAuditService#denyAsNotFound",
          // The caller's own identity or workspace failing to resolve - no caller-supplied id.
          "AccessControlService#requireActingMember",
          "WorkspaceMemberService#requireActingMember",
          "NetWorthService#getNetWorth",
          "CategoryService#requireEditor",
          // A capability gate ("may this caller create securities at all"), not one object.
          "SecurityService#requireMayCreate",
          // Globally shared reference data, identical for every tenant.
          "SecurityService#get",
          "SecurityService#lookup",
          "InstitutionService#applyCatalogueEntry",
          // Missing FX data, not an authorization decision.
          "FxRateService#getRate",
          "FxRateService#noConversionRateAvailable",
          // SYSTEM_ADMINISTRATOR-only user management (SecurityConfig), outside tenant data.
          "AdminUserService#findUserOrThrow",
          "AdminUserService#findUserForUpdateOrThrow",
          "AdminUserService#extractTargetOrThrow");

  @ArchTest
  static final ArchRule object_level_services_do_not_construct_raw_not_found =
      classes()
          .that()
          .resideInAPackage("..service..")
          .should(constructRawNotFoundOnlyIn(REVIEWED_RAW_NOT_FOUND_METHODS))
          .because(
              "single-resource authorization denials must go through AuthorizationDenialAuditService"
                  + " so US-28-02 records them and returns one non-enumerating 404");

  // A method is named Class#method; a lambda counts as the method it is written in.
  private static ArchCondition<JavaClass> constructRawNotFoundOnlyIn(Set<String> reviewed) {
    return new ArchCondition<>("construct a raw 404 only in a reviewed method") {
      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        for (JavaFieldAccess access : javaClass.getFieldAccessesFromSelf()) {
          if (access.getTargetOwner().isEquivalentTo(HttpStatus.class)
              && "NOT_FOUND".equals(access.getName())) {
            String method = javaClass.getSimpleName() + "#" + enclosingMethod(access.getOrigin());
            if (!reviewed.contains(method)) {
              events.add(
                  SimpleConditionEvent.violated(access, method + ": " + access.getDescription()));
            }
          }
        }
      }
    };
  }

  private static String enclosingMethod(JavaCodeUnit codeUnit) {
    String name = codeUnit.getName();
    return name.startsWith("lambda$") ? name.substring(7, name.lastIndexOf('$')) : name;
  }

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
