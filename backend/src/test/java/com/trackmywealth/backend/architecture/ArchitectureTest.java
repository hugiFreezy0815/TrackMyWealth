package com.trackmywealth.backend.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
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
import com.trackmywealth.backend.client.FxRateProvider;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Portfolio;
import com.trackmywealth.backend.security.SystemWorkspaceContext;
import com.trackmywealth.backend.service.AccountService;
import com.trackmywealth.backend.service.PortfolioService;
import com.trackmywealth.backend.service.TransferRecheckService;
import com.trackmywealth.backend.testsupport.DocumentedHandlers;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Entity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Codifies the layering and package conventions from {@code
 * docs/architecture/development-standards.md} as executable rules, not just documentation - see
 * that file for the full rationale and for how this class is expected to grow.
 *
 * <p>The rules were written before the packages they cover (controller, service, repository,
 * entity, dto) had any classes - ArchUnit rules over an empty package are vacuously true - so each
 * started enforcing the moment its first class landed instead of being written retroactively, the
 * same pattern EPIC-28's cross-tenant test suite (US-28-04) uses for entity coverage. All of these
 * packages are populated now; a new package gets its rule the same way, before its first class.
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

  // #223: SystemWorkspaceContext runs work inside any workspace it is given, so only the system's
  // own background work may reach it - Quartz jobs and the re-detection they trigger, which take
  // their workspace ids from the system's own data. A request path must never act as a workspace
  // it chose itself.
  @ArchTest
  static final ArchRule only_background_work_acts_as_a_workspace =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.trackmywealth.backend.job..")
          .and()
          .doNotHaveFullyQualifiedName(TransferRecheckService.class.getName())
          .and()
          .doNotHaveFullyQualifiedName(SystemWorkspaceContext.class.getName())
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName(SystemWorkspaceContext.class.getName())
          .because(
              "it binds a workspace without a user; only background work with workspace ids from"
                  + " the system's own data may use it (#223)");

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

  // #226: exactly one FxRateProvider is loaded, the one app.fx.import.provider names. A service
  // reaching a concrete provider would tie the import back to it and fail to wire once another is
  // selected; services read a provider's name, source and hub from its FxRateProviderDefinition.
  @ArchTest
  static final ArchRule services_do_not_depend_on_a_concrete_fx_rate_provider =
      noClasses()
          .that()
          .resideInAPackage("..service..")
          .should()
          .dependOnClassesThat(JavaClass.Predicates.implement(FxRateProvider.class))
          .because(
              "the FX provider is selected by configuration (#226) - depend on FxRateProvider and"
                  + " FxRateProviderDefinition, never on one implementation")
          .allowEmptyShould(true);

  // #226: a client reads its settings from ..config.. (e.g. EcbFxRateProvider's properties), so the
  // reverse direction would make the two packages a cycle. Client-owned beans and constants stay in
  // ..client.., and a settings default that names a provider is a literal pinned by a test.
  @ArchTest
  static final ArchRule config_does_not_depend_on_clients =
      noClasses()
          .that()
          .resideInAPackage("..config..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..client..")
          .because(
              "clients depend on their settings in ..config.., and the reverse would make the two"
                  + " packages a cycle (#226)")
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
  // drive which account_type may be added under a portfolio - a securities depot
  // under a "Bank" and a cash account under a "Pension Provider" are both exactly as valid as any
  // other combination. Unlike account_type (which legitimately drives behaviour inside
  // AccountService, translated into capability flags), institution_type has no sanctioned
  // exception at all for account creation - PortfolioService reads it only to manage the
  // institution itself (e.g. rendering its own summary), never to gate what account_type a
  // caller may create. Same reasoning as only_account_service_branches_on_account_type for why
  // this doesn't need allowEmptyShould(true): PortfolioService already has real classes to
  // check against today.
  @ArchTest
  static final ArchRule only_institution_service_reads_institution_type =
      noClasses()
          .that()
          .areNotAssignableTo(PortfolioService.class)
          .should()
          .callMethod(Portfolio.class, "getInstitutionType")
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
          "PortfolioService#applyCatalogueEntry",
          // An account the caller was already cleared for simply has no opening balance.
          "OpeningBalanceService#notRecorded",
          // A row number of an import batch the caller was already cleared for (#230): row numbers
          // are 1..n of that batch, so nothing is learned by trying others.
          "ImportBatchService#requireRow",
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

  // PR #267 review: pdf holds the PDFBox extensions that bound the reading of an untrusted PDF
  // (BoundedPdfParser, PdfStreamBudget, ...) and, since #268, the reading of a statement's page
  // layout from its words' positions (PdfColumns, PageFurniture, PdfBooking). It knows nothing of
  // the application - it throws PdfLimitException, which the reading service maps to an API error,
  // and takes no template - and only services use it.
  @ArchTest
  static final ArchRule pdf_depends_on_nothing_of_the_application =
      noClasses()
          .that()
          .resideInAPackage("..pdf..")
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.resideInAPackage("com.trackmywealth.backend..")
                  .and(DescribedPredicate.not(JavaClass.Predicates.resideInAPackage("..pdf.."))))
          .because("the PDF limits are plain PDFBox extensions; a service maps their exceptions")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule only_services_read_pdfs =
      classes()
          .that()
          .resideInAPackage("..pdf..")
          .should()
          .onlyBeAccessed()
          .byAnyPackage("..pdf..", "..service..")
          .because("an untrusted PDF is read only through PdfImportReaderService's limits")
          .allowEmptyShould(true);

  // #350: camt reads an untrusted ISO 20022 CAMT file - streaming StAX without DTDs or entities,
  // ZIP archives within inflation bounds - into its own records, as pdf reads a PDF. It knows
  // nothing of the application: it throws CamtLimitException and CamtFormatException, which
  // CamtImportReaderService maps to API errors, and only services use it.
  @ArchTest
  static final ArchRule camt_depends_on_nothing_of_the_application =
      noClasses()
          .that()
          .resideInAPackage("..camt..")
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.resideInAPackage("com.trackmywealth.backend..")
                  .and(DescribedPredicate.not(JavaClass.Predicates.resideInAPackage("..camt.."))))
          .because("the CAMT limits are plain JDK readers; a service maps their exceptions")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule only_services_read_camt_files =
      classes()
          .that()
          .resideInAPackage("..camt..")
          .should()
          .onlyBeAccessed()
          .byAnyPackage("..camt..", "..service..")
          .because("an untrusted CAMT file is read only through CamtImportReaderService's limits")
          .allowEmptyShould(true);

  // #373 (ADR 0006): a paged list answers with dto.PageResponse. Spring Data's Page (a Slice) has
  // no stable JSON of its own - its shape changed with framework upgrades before.
  @ArchTest
  static final ArchRule controllers_do_not_return_spring_data_pages =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("..controller..")
          .should(notReturnASpringDataSlice())
          .because(
              "a paged list answers with PageResponse (#373, ADR 0006), whose shape this API owns")
          .allowEmptyShould(true);

  // #373: page, size and sort are read by web.PagedArgumentResolver only; a Pageable (or Sort)
  // without @Paged would take any client sort property through Spring Data's own resolver.
  @ArchTest
  static final ArchRule paged_parameters_declare_their_sort_whitelist =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("..controller..")
          .should(declarePagingThroughPaged())
          .because(
              "a paged list's sort must be checked against its whitelist (#373) - annotate the"
                  + " Pageable with @Paged")
          .allowEmptyShould(true);

  // US-29-02 (#372): the committed api/openapi.yaml groups every operation under a tag and names it
  // by an id the code chose, never springdoc's fallback (the method name, numbered on a clash).
  // Exactly what springdoc documents (DocumentedHandlers): a @Controller or @RestController
  // handler with @ResponseBody or @Operation, neither it nor its class @Hidden.
  // Reflection only for controllers: the annotation check on ArchUnit's own model comes first.
  private static final DescribedPredicate<JavaClass> API_CONTROLLERS =
      DescribedPredicate.describe(
          "are controllers with operations in the API contract",
          type -> isController(type) && DocumentedHandlers.isDocumentedController(type.reflect()));

  private static final DescribedPredicate<JavaMethod> API_OPERATIONS =
      DescribedPredicate.describe(
          "are operations of the API contract",
          method ->
              isController(method.getOwner())
                  && method.isMetaAnnotatedWith(RequestMapping.class)
                  && DocumentedHandlers.isDocumentedOperation(
                      method.getOwner().reflect(), method.reflect()));

  @ArchTest
  static final ArchRule api_controllers_declare_their_tag =
      classes()
          .that(API_CONTROLLERS)
          .should()
          .beAnnotatedWith(Tag.class)
          .because(
              "every operation in api/openapi.yaml belongs to one resource area (#372) - annotate"
                  + " the controller with @Tag, or @Hidden when it is not part of the API");

  @ArchTest
  static final ArchRule api_operations_declare_their_operation_id =
      methods()
          .that(API_OPERATIONS)
          .should(declareAnExplicitOperationId())
          .because(
              "generated clients call operations by their id (#372): it must be chosen, stable and"
                  + " unique - annotate the method with @Operation(operationId = ..., summary = ...),"
                  + " or @Hidden when it is not part of the API");

  // US-29-02 (#372): springdoc documents every success as 200; @SuccessStatus documents the
  // statuses a handler answers instead. Both directions: an answered status not documented, and a
  // documented one never answered (which would replace the real 200 in the contract). The bytecode
  // half; DocumentedSuccessStatusFilter checks the status actually sent in every test request.
  @ArchTest
  static final ArchRule api_operations_answer_their_documented_success_statuses =
      methods()
          .that(API_OPERATIONS)
          .should(answerTheSuccessStatusesTheyDocument())
          .because(
              "springdoc cannot see the status inside a ResponseEntity and documents 200 (#372) -"
                  + " declare the statuses the method answers with @SuccessStatus, each set from an"
                  + " HttpStatus constant or a ResponseEntity factory");

  // US-29-09 (#455): springdoc cannot see the headers set on a ResponseEntity either; @SendsHeaders
  // documents them. Both directions, like the statuses: a header sent but not declared, and one
  // declared but never sent or on no documented status it comes with. The bytecode half;
  // DocumentedSuccessStatusFilter checks the headers actually sent in every test request.
  @ArchTest
  static final ArchRule api_operations_send_their_documented_headers =
      methods()
          .that(API_OPERATIONS)
          .should(sendTheHeadersTheyDocument())
          .because(
              "generated clients read the ETag for If-Match and the Location of a new resource only"
                  + " from the contract (#455) - declare the headers the method sends with"
                  + " @SendsHeaders");

  @ArchTest
  static final ArchRule no_standard_streams =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest
  static final ArchRule no_java_util_logging =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  @ArchTest
  static final ArchRule no_generic_exceptions =
      GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  private static ArchCondition<JavaMethod> declareAnExplicitOperationId() {
    return new ArchCondition<>("declare @Operation with an operationId and a summary") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean declared =
            method
                .tryGetAnnotationOfType(Operation.class)
                .filter(operation -> !operation.operationId().isBlank())
                .filter(operation -> !operation.summary().isBlank())
                .isPresent();
        if (!declared) {
          events.add(
              SimpleConditionEvent.violated(
                  method, method.getFullName() + " has no @Operation operationId and summary"));
        }
      }
    };
  }

  private static boolean isController(JavaClass type) {
    return type.isAnnotatedWith(Controller.class) || type.isMetaAnnotatedWith(Controller.class);
  }

  private static ArchCondition<JavaMethod> answerTheSuccessStatusesTheyDocument() {
    return new ArchCondition<>("answer exactly the success statuses they document") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        SuccessStatusEvidence evidence = SuccessStatusEvidence.of(method);
        Set<HttpStatus> documented = DocumentedHandlers.documentedSuccessStatuses(method.reflect());
        List<String> problems = new ArrayList<>();
        if (!evidence.unreadable().isEmpty()) {
          problems.add("sets a status this rule cannot read: " + evidence.unreadable());
        }
        Set<HttpStatus> undocumented = EnumSet.noneOf(HttpStatus.class);
        undocumented.addAll(evidence.answered());
        undocumented.removeAll(documented);
        if (!undocumented.isEmpty()) {
          problems.add("answers " + undocumented + " without declaring it in @SuccessStatus");
        }
        Set<HttpStatus> neverAnswered = EnumSet.copyOf(documented);
        neverAnswered.removeAll(evidence.answered());
        // A ResponseEntity built in another class shows no status here: only springdoc's default
        // 200 is taken on trust then (the runtime check still sees it); a declared one is not.
        boolean defaultTakenOnTrust =
            evidence.answered().isEmpty() && documented.equals(EnumSet.of(HttpStatus.OK));
        if (!neverAnswered.isEmpty() && !defaultTakenOnTrust) {
          problems.add(
              "documents "
                  + neverAnswered
                  + (method.isAnnotatedWith(SuccessStatus.class)
                      ? " in @SuccessStatus"
                      : " (springdoc's default)")
                  + " but never answers it");
        }
        problems.forEach(
            problem ->
                events.add(
                    SimpleConditionEvent.violated(method, method.getFullName() + " " + problem)));
      }
    };
  }

  private static ArchCondition<JavaMethod> sendTheHeadersTheyDocument() {
    return new ArchCondition<>("send exactly the response headers they document") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        Set<ResponseHeader> sent = SuccessStatusEvidence.of(method).sends();
        Set<ResponseHeader> documented = DocumentedHandlers.documentedHeaders(method.reflect());
        Set<HttpStatus> statuses = DocumentedHandlers.documentedSuccessStatuses(method.reflect());
        List<String> problems = new ArrayList<>();
        for (ResponseHeader header : ResponseHeader.values()) {
          if (sent.contains(header) && !documented.contains(header)) {
            problems.add("sends " + header + " without declaring it in @SendsHeaders");
          } else if (documented.contains(header) && !sent.contains(header)) {
            problems.add("declares " + header + " in @SendsHeaders but never sends it");
          }
          if (documented.contains(header) && statuses.stream().noneMatch(header::comesWith)) {
            problems.add(
                "declares "
                    + header
                    + " in @SendsHeaders, but none of its success statuses "
                    + statuses
                    + " carries it");
          }
        }
        problems.forEach(
            problem ->
                events.add(
                    SimpleConditionEvent.violated(method, method.getFullName() + " " + problem)));
      }
    };
  }

  private static ArchCondition<JavaMethod> notReturnASpringDataSlice() {
    return new ArchCondition<>("not return a Spring Data Page or Slice") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean returnsSlice =
            method.getReturnType().getAllInvolvedRawTypes().stream()
                .anyMatch(type -> type.isAssignableTo(Slice.class));
        if (returnsSlice) {
          events.add(
              SimpleConditionEvent.violated(
                  method, method.getFullName() + " returns " + method.getReturnType().getName()));
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> declarePagingThroughPaged() {
    return new ArchCondition<>("annotate every Pageable or Sort parameter with @Paged") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        for (JavaParameter parameter : method.getParameters()) {
          JavaClass type = parameter.getRawType();
          boolean paging = type.isAssignableTo(Pageable.class) || type.isAssignableTo(Sort.class);
          boolean whitelisted =
              type.isEquivalentTo(Pageable.class) && parameter.isAnnotatedWith(Paged.class);
          if (paging && !whitelisted) {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    method.getFullName()
                        + " takes "
                        + type.getSimpleName()
                        + " without @Paged's sort whitelist"));
          }
        }
      }
    };
  }

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
