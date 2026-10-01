package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CategoryResponse;
import com.trackmywealth.backend.dto.CreateCategoryRequest;
import com.trackmywealth.backend.dto.UpdateCategoryRequest;
import com.trackmywealth.backend.entity.Category;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.entity.WorkspaceCategoryOverride;
import com.trackmywealth.backend.repository.CategoryRepository;
import com.trackmywealth.backend.repository.WorkspaceCategoryOverrideRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-08-04: the taxonomy rules {@link CategoryService} owns (depth, cycles, protected codes, code
 * generation, cascading deactivation, overrides of shared defaults, FR-LIF-001 deletion), with the
 * repositories mocked. {@code CategoryControllerTest} covers the same flows against PostgreSQL.
 */
class CategoryServiceTest {

  private static final UUID WORKSPACE = UUID.randomUUID();
  private static final UUID MEMBER = UUID.randomUUID();
  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", WORKSPACE, UUID.randomUUID());

  private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
  private final WorkspaceCategoryOverrideRepository overrideRepository =
      mock(WorkspaceCategoryOverrideRepository.class);
  private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final CategoryService service =
      new CategoryService(
          categoryRepository, overrideRepository, workspaceRepository, accessControlService);

  private final List<Category> categories = new ArrayList<>();
  private final List<WorkspaceCategoryOverride> overrides = new ArrayList<>();

  private Category leisure;
  private Category uncategorized;
  private Category transferInternal;

  @BeforeEach
  void setUp() {
    leisure = shared("LEISURE", "Leisure", "Freizeit", null);
    uncategorized = shared("UNCATEGORIZED", "Uncategorized", "Nicht kategorisiert", null);
    transferInternal =
        shared("TRANSFER_INTERNAL", "Internal Transfer", "Interne Überweisung", null);

    when(categoryRepository.findVisibleTo(WORKSPACE)).thenAnswer(inv -> List.copyOf(categories));
    when(overrideRepository.findByWorkspaceId(WORKSPACE)).thenAnswer(inv -> List.copyOf(overrides));
    when(accessControlService.requireActingMember(ACTOR)).thenReturn(MEMBER);
    // #192: a caller-supplied category id that doesn't resolve goes through the audited denial,
    // which answers with the generic 404 (the audit itself is AuthorizationDenialAuditService's).
    when(accessControlService.denyAsNotFound(any(), any(), any()))
        .thenAnswer(inv -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found."));
    when(accessControlService.workspaceAccessLevel(MEMBER, WORKSPACE))
        .thenReturn(AccessLevelValues.EDIT);
    when(workspaceRepository.findByIdForUpdate(WORKSPACE)).thenReturn(Optional.of(new Workspace()));
    // Writes land in the fixture lists, so a later call in the same test sees them, as it would
    // see the database.
    when(categoryRepository.saveAndFlush(any(Category.class)))
        .thenAnswer(inv -> persist(inv.getArgument(0), categories));
    when(overrideRepository.saveAndFlush(any(WorkspaceCategoryOverride.class)))
        .thenAnswer(inv -> persist(inv.getArgument(0), overrides));
    doAnswer(inv -> overrides.remove(inv.<WorkspaceCategoryOverride>getArgument(0)))
        .when(overrideRepository)
        .delete(any(WorkspaceCategoryOverride.class));
  }

  // --- create --------------------------------------------------------------------------------

  @Nested
  class Create {

    @Test
    void aSubcategoryUnderASharedDefaultBelongsToTheWorkspaceAndPointsAtTheDefault() {
      CategoryResponse created =
          service.create(
              new CreateCategoryRequest(
                  leisure.getId(), "Streaming Subscriptions", "Streaming-Abos"),
              ACTOR);

      ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
      verify(categoryRepository).saveAndFlush(saved.capture());
      assertThat(saved.getValue().getWorkspaceId()).isEqualTo(WORKSPACE);
      assertThat(saved.getValue().getParentCategoryId()).isEqualTo(leisure.getId());
      assertThat(saved.getValue().isSystemDefault()).isFalse();
      assertThat(created.code()).isEqualTo("WS_STREAMING_SUBSCRIPTIONS");
      assertThat(created.level()).isEqualTo(2);
      assertThat(created.systemDefault()).isFalse();
      assertThat(created.active()).isTrue();
    }

    @Test
    void theThirdLevelIsAllowedButAFourthIsNot() {
      Category level2 = own("WS_L2", "Level 2", "Ebene 2", leisure);
      Category level3 = own("WS_L3", "Level 3", "Ebene 3", level2);

      assertThat(
              service
                  .create(new CreateCategoryRequest(level2.getId(), "Other 3", "Andere 3"), ACTOR)
                  .level())
          .isEqualTo(3);
      assertStatus(
          () ->
              service.create(
                  new CreateCategoryRequest(level3.getId(), "Level 4", "Ebene 4"), ACTOR),
          HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aProtectedCategoryCannotHaveSubcategories() {
      assertStatus(
          () ->
              service.create(
                  new CreateCategoryRequest(uncategorized.getId(), "Child", "Kind"), ACTOR),
          HttpStatus.UNPROCESSABLE_CONTENT);
      assertStatus(
          () ->
              service.create(
                  new CreateCategoryRequest(transferInternal.getId(), "Child", "Kind"), ACTOR),
          HttpStatus.UNPROCESSABLE_CONTENT);
      verify(categoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void anInactiveParentCannotReceiveANewSubcategory() {
      override(leisure, null, null, false);

      assertStatus(
          () -> service.create(new CreateCategoryRequest(leisure.getId(), "Child", "Kind"), ACTOR),
          HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void anUnknownOrForeignParentIsNotFound() {
      UUID unknownParent = UUID.randomUUID();

      assertStatus(
          () -> service.create(new CreateCategoryRequest(unknownParent, "Child", "Kind"), ACTOR),
          HttpStatus.NOT_FOUND);

      // #192: and the denial is the audited one, naming the caller and the id it asked for.
      verify(accessControlService).denyAsNotFound(ACTOR, "Category", unknownParent);
    }

    @ParameterizedTest
    @CsvSource({"leisure, Something else", "Other, FREIZEIT"})
    void aLabelAlreadyUsedByASiblingIsAConflictIgnoringCase(String nameEn, String nameDe) {
      assertStatus(
          () -> service.create(new CreateCategoryRequest(null, nameEn, nameDe), ACTOR),
          HttpStatus.CONFLICT);
    }

    @Test
    void aSiblingIsComparedByItsEffectiveLabelIncludingThisWorkspacesRelabelling() {
      override(leisure, "Fun", null, null);

      assertStatus(
          () -> service.create(new CreateCategoryRequest(null, "fun", "Spass"), ACTOR),
          HttpStatus.CONFLICT);
      assertThat(service.create(new CreateCategoryRequest(null, "Leisure", "Musse"), ACTOR).code())
          .isEqualTo("WS_LEISURE");
    }

    @Test
    void theSameLabelUnderADifferentParentIsFine() {
      own("WS_SPORT", "Sport", "Sport", leisure);

      assertThat(service.create(new CreateCategoryRequest(null, "Sport", "Sport"), ACTOR).code())
          .isEqualTo("WS_SPORT_2");
    }

    @Test
    void aMemberWithoutWorkspaceEditChangesNothing() {
      doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found."))
          .when(accessControlService)
          .requireWorkspaceAccess(ACTOR, WORKSPACE, AccessLevelValues.EDIT);

      assertStatus(
          () -> service.create(new CreateCategoryRequest(null, "Hobby", "Hobby"), ACTOR),
          HttpStatus.NOT_FOUND);
      verify(categoryRepository, never()).saveAndFlush(any());
    }
  }

  // --- code generation -----------------------------------------------------------------------

  @Nested
  class WorkspaceCode {

    @ParameterizedTest
    @CsvSource({
      "Streaming Subscriptions, WS_STREAMING_SUBSCRIPTIONS",
      "Café & Restaurants, WS_CAFE_RESTAURANTS",
      "Gebühren (Bank), WS_GEBUHREN_BANK",
      "Straße, WS_STRASSE",
      "'  --Kids 2026--  ', WS_KIDS_2026",
      "!!!, WS_CATEGORY"
    })
    void isAnUpperCaseAsciiSlugOfTheEnglishLabel(String nameEn, String expected) {
      assertThat(CategoryService.workspaceCodeFor(nameEn, Set.of())).isEqualTo(expected);
    }

    @Test
    void aTakenCodeGetsTheNextFreeNumericSuffix() {
      assertThat(CategoryService.workspaceCodeFor("Pets", Set.of("WS_PETS", "WS_PETS_2")))
          .isEqualTo("WS_PETS_3");
    }

    @Test
    void aLongLabelIsTruncatedWithoutATrailingUnderscore() {
      String code =
          CategoryService.workspaceCodeFor(
              "A very long category name that keeps going and going", Set.of());

      assertThat(code).startsWith("WS_A_VERY_LONG").doesNotEndWith("_");
      assertThat(code.length()).isLessThanOrEqualTo(3 + 40);
    }
  }

  // --- update --------------------------------------------------------------------------------

  @Nested
  class Update {

    @Test
    void relabellingASharedDefaultWritesAnOverrideAndNeverTouchesTheSharedRow() {
      CategoryResponse renamed =
          service.update(
              leisure.getId(), new UpdateCategoryRequest(null, "Free Time", "Freizeit", 0), ACTOR);

      ArgumentCaptor<WorkspaceCategoryOverride> saved =
          ArgumentCaptor.forClass(WorkspaceCategoryOverride.class);
      verify(overrideRepository).saveAndFlush(saved.capture());
      assertThat(saved.getValue().getWorkspaceId()).isEqualTo(WORKSPACE);
      assertThat(saved.getValue().getNameEn()).isEqualTo("Free Time");
      assertThat(saved.getValue().getNameDe()).as("unchanged label inherits").isNull();
      assertThat(leisure.getNameEn()).isEqualTo("Leisure");
      verify(categoryRepository, never()).saveAndFlush(any());
      assertThat(renamed.code()).as("FR-CAT-008: the code never changes").isEqualTo("LEISURE");
      assertThat(renamed.nameEn()).isEqualTo("Free Time");
      assertThat(renamed.customised()).isTrue();
    }

    @Test
    void relabellingADefaultBackToTheShippedLabelsRemovesTheOverride() {
      WorkspaceCategoryOverride existing = override(leisure, "Free Time", null, null);

      CategoryResponse restored =
          service.update(
              leisure.getId(), new UpdateCategoryRequest(null, "Leisure", "Freizeit", 1), ACTOR);

      verify(overrideRepository).delete(existing);
      assertThat(restored.customised()).isFalse();
      assertThat(restored.nameEn()).isEqualTo("Leisure");
    }

    @Test
    void aSharedDefaultKeepsItsShippedPosition() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);

      assertStatus(
          () ->
              service.update(
                  leisure.getId(),
                  new UpdateCategoryRequest(hobby.getId(), "Leisure", "Freizeit", 0),
                  ACTOR),
          HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void anOwnCategoryIsRenamedAndMovedInPlaceKeepingItsCode() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);

      CategoryResponse moved =
          service.update(
              hobby.getId(),
              new UpdateCategoryRequest(leisure.getId(), "Hobbies", "Hobbys", 0),
              ACTOR);

      verify(categoryRepository).saveAndFlush(hobby);
      assertThat(moved.parentId()).isEqualTo(leisure.getId());
      assertThat(moved.level()).isEqualTo(2);
      assertThat(moved.nameEn()).isEqualTo("Hobbies");
      assertThat(moved.code()).isEqualTo("WS_HOBBY");
    }

    @Test
    void aCategoryCannotBeMovedUnderItselfOrItsOwnSubtree() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);
      Category music = own("WS_MUSIC", "Music", "Musik", hobby);

      assertStatus(
          () ->
              service.update(
                  hobby.getId(),
                  new UpdateCategoryRequest(hobby.getId(), "Hobby", "Hobby", 0),
                  ACTOR),
          HttpStatus.UNPROCESSABLE_CONTENT);
      assertStatus(
          () ->
              service.update(
                  hobby.getId(),
                  new UpdateCategoryRequest(music.getId(), "Hobby", "Hobby", 0),
                  ACTOR),
          HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aMoveMayNotPushTheMovedSubtreeBeyondThreeLevels() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);
      own("WS_MUSIC", "Music", "Musik", hobby);
      Category sport = own("WS_SPORT", "Sport", "Sport", leisure);

      // Hobby (height 2) under Sport (level 2) would put Music at level 4.
      assertStatus(
          () ->
              service.update(
                  hobby.getId(),
                  new UpdateCategoryRequest(sport.getId(), "Hobby", "Hobby", 0),
                  ACTOR),
          HttpStatus.UNPROCESSABLE_CONTENT);
      // Under Leisure (level 1) it fits exactly.
      assertThat(
              service
                  .update(
                      hobby.getId(),
                      new UpdateCategoryRequest(leisure.getId(), "Hobby", "Hobby", 0),
                      ACTOR)
                  .level())
          .isEqualTo(2);
    }

    @Test
    void movingToTheTopLevelIsAllowed() {
      Category sport = own("WS_SPORT", "Sport", "Sport", leisure);

      CategoryResponse moved =
          service.update(
              sport.getId(), new UpdateCategoryRequest(null, "Sport", "Sport", 0), ACTOR);

      assertThat(moved.parentId()).isNull();
      assertThat(moved.level()).isEqualTo(1);
    }
  }

  // --- activation ----------------------------------------------------------------------------

  @Nested
  class Activation {

    @Test
    void deactivatingASharedDefaultCascadesAndOnlyWritesThisWorkspacesOverride() {
      Category sport = own("WS_SPORT", "Sport", "Sport", leisure);
      Category football = own("WS_FOOTBALL", "Football", "Fussball", sport);

      CategoryResponse result = service.deactivate(leisure.getId(), ACTOR);

      assertThat(result.active()).isFalse();
      assertThat(result.customised()).isTrue();
      assertThat(leisure.isActive()).as("shared row untouched").isTrue();
      ArgumentCaptor<WorkspaceCategoryOverride> saved =
          ArgumentCaptor.forClass(WorkspaceCategoryOverride.class);
      verify(overrideRepository).saveAndFlush(saved.capture());
      assertThat(saved.getValue().getActive()).isFalse();
      assertThat(sport.isActive()).isFalse();
      assertThat(football.isActive()).isFalse();
    }

    @Test
    void protectedCategoriesCannotBeDeactivated() {
      assertStatus(
          () -> service.deactivate(uncategorized.getId(), ACTOR), HttpStatus.UNPROCESSABLE_CONTENT);
      assertStatus(
          () -> service.deactivate(transferInternal.getId(), ACTOR),
          HttpStatus.UNPROCESSABLE_CONTENT);
      verify(overrideRepository, never()).saveAndFlush(any());
    }

    @Test
    void deactivatingAnInactiveCategoryIsANoOp() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);
      hobby.setActive(false);

      assertThat(service.deactivate(hobby.getId(), ACTOR).active()).isFalse();
      verify(categoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void reactivationNeedsAnActiveParentAndDoesNotCascade() {
      Category sport = own("WS_SPORT", "Sport", "Sport", leisure);
      Category football = own("WS_FOOTBALL", "Football", "Fussball", sport);
      service.deactivate(leisure.getId(), ACTOR);

      assertStatus(() -> service.activate(sport.getId(), ACTOR), HttpStatus.UNPROCESSABLE_CONTENT);

      CategoryResponse reactivated = service.activate(leisure.getId(), ACTOR);
      assertThat(reactivated.active()).isTrue();
      assertThat(reactivated.customised()).as("override back to inherit is removed").isFalse();
      verify(overrideRepository).delete(any(WorkspaceCategoryOverride.class));
      assertThat(sport.isActive()).isFalse();
      assertThat(football.isActive()).isFalse();
    }

    @Test
    void anInactiveCategoryCannotBeAssignedButAnActiveOneCan() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);
      override(leisure, null, null, false);

      service.requireAssignable(hobby.getId(), WORKSPACE);
      assertStatus(
          () -> service.requireAssignable(leisure.getId(), WORKSPACE),
          HttpStatus.UNPROCESSABLE_CONTENT);
      assertStatus(
          () -> service.requireAssignable(UUID.randomUUID(), WORKSPACE), HttpStatus.NOT_FOUND);
    }
  }

  // --- delete (FR-LIF-001) -------------------------------------------------------------------

  @Nested
  class Delete {

    @Test
    void aSharedDefaultIsNeverDeleted() {
      assertStatus(() -> service.delete(leisure.getId(), ACTOR), HttpStatus.CONFLICT);
      verify(categoryRepository, never()).delete(any());
    }

    @Test
    void anOwnCategoryInUseIsNotDeleted() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);
      when(categoryRepository.isReferenced(hobby.getId())).thenReturn(true);

      assertStatus(() -> service.delete(hobby.getId(), ACTOR), HttpStatus.CONFLICT);
      verify(categoryRepository, never()).delete(any());
    }

    @Test
    void aNeverUsedOwnCategoryIsDeleted() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);

      service.delete(hobby.getId(), ACTOR);

      verify(categoryRepository).delete(hobby);
    }
  }

  // --- list ----------------------------------------------------------------------------------

  @Test
  void theListIsInTreeOrderAndHidesInactiveCategoriesUnlessAsked() {
    Category sport = own("WS_SPORT", "Sport", "Sport", leisure);
    Category archived = own("WS_ARCHIVED", "Archived", "Archiviert", leisure);
    archived.setActive(false);

    List<String> active = service.list(false, ACTOR).stream().map(CategoryResponse::code).toList();
    List<String> all = service.list(true, ACTOR).stream().map(CategoryResponse::code).toList();

    assertThat(active)
        .containsExactly("TRANSFER_INTERNAL", "LEISURE", sport.getCode(), "UNCATEGORIZED");
    assertThat(all)
        .containsExactly(
            "TRANSFER_INTERNAL", "LEISURE", archived.getCode(), sport.getCode(), "UNCATEGORIZED");
  }

  // --- review follow-ups: serialization, versions, inherited activity, batch, capability -----

  @Nested
  class Concurrency {

    @Test
    void everyChangeLocksTheWorkspaceRowBeforeReadingTheTree() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);

      service.create(new CreateCategoryRequest(null, "Pets", "Haustiere"), ACTOR);
      service.update(hobby.getId(), new UpdateCategoryRequest(null, "Hobbies", "Hobbys", 0), ACTOR);
      service.deactivate(hobby.getId(), ACTOR);
      service.activate(hobby.getId(), ACTOR);
      service.delete(hobby.getId(), ACTOR);

      verify(workspaceRepository, times(5)).findByIdForUpdate(WORKSPACE);
    }

    @Test
    void aMemberWithoutEditNeverTakesTheLock() {
      doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found."))
          .when(accessControlService)
          .requireWorkspaceAccess(ACTOR, WORKSPACE, AccessLevelValues.EDIT);

      assertStatus(
          () -> service.create(new CreateCategoryRequest(null, "Pets", "Haustiere"), ACTOR),
          HttpStatus.NOT_FOUND);
      verify(workspaceRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void readsDoNotLock() {
      service.list(true, ACTOR);
      service.get(leisure.getId(), ACTOR);

      verify(workspaceRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void anUpdateBasedOnAStaleVersionIsAConflictAndChangesNothing() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);
      ReflectionTestUtils.setField(hobby, "version", 3);

      assertStatus(
          () ->
              service.update(
                  hobby.getId(), new UpdateCategoryRequest(null, "Hobbies", "Hobbys", 2), ACTOR),
          HttpStatus.CONFLICT);
      verify(categoryRepository, never()).saveAndFlush(any());
      assertThat(
              service
                  .update(
                      hobby.getId(), new UpdateCategoryRequest(null, "Hobbies", "Hobbys", 3), ACTOR)
                  .nameEn())
          .isEqualTo("Hobbies");
    }

    @Test
    void aDefaultsVersionTracksThisWorkspacesOverride() {
      assertThat(service.get(leisure.getId(), ACTOR).version()).as("no override").isZero();
      WorkspaceCategoryOverride existing = override(leisure, "Fun", null, null);
      ReflectionTestUtils.setField(existing, "version", 4);

      assertThat(service.get(leisure.getId(), ACTOR).version()).isEqualTo(5);
      assertStatus(
          () ->
              service.update(
                  leisure.getId(),
                  new UpdateCategoryRequest(null, "Free Time", "Freizeit", 0),
                  ACTOR),
          HttpStatus.CONFLICT);
      verify(overrideRepository, never()).saveAndFlush(any());
    }
  }

  @Nested
  class DefaultPosition {

    @Test
    void aNestedDefaultIsRelabelledWithoutRepeatingItsParent() {
      Category streaming = shared("STREAMING", "Streaming", "Streaming", leisure);

      CategoryResponse renamed =
          service.update(
              streaming.getId(), new UpdateCategoryRequest(null, "Video", "Video", 0), ACTOR);

      assertThat(renamed.parentId()).isEqualTo(leisure.getId());
      assertThat(renamed.nameEn()).isEqualTo("Video");
      assertThat(streaming.getParentCategoryId()).isEqualTo(leisure.getId());
    }

    @Test
    void aNestedDefaultCanStillNotBeMovedToAnotherParent() {
      Category streaming = shared("STREAMING", "Streaming", "Streaming", leisure);
      Category other = shared("OTHER", "Other", "Sonstiges", null);

      assertStatus(
          () ->
              service.update(
                  streaming.getId(),
                  new UpdateCategoryRequest(other.getId(), "Streaming", "Streaming", 0),
                  ACTOR),
          HttpStatus.UNPROCESSABLE_CONTENT);
    }
  }

  @Nested
  class InheritedActivity {

    // E.g. a default a later reference package adds under a default this workspace deactivated:
    // its own flag is active, but it sits under an inactive ancestor.
    @Test
    void aCategoryUnderAnInactiveAncestorIsInactiveAndNotAssignable() {
      override(leisure, null, null, false);
      Category streaming = shared("STREAMING", "Streaming", "Streaming", leisure);
      Category netflix = own("WS_NETFLIX", "Netflix", "Netflix", streaming);

      assertThat(service.get(streaming.getId(), ACTOR).active()).isFalse();
      assertThat(service.get(netflix.getId(), ACTOR).active()).isFalse();
      assertStatus(
          () -> service.requireAssignable(netflix.getId(), WORKSPACE),
          HttpStatus.UNPROCESSABLE_CONTENT);
      assertStatus(
          () -> service.create(new CreateCategoryRequest(streaming.getId(), "X", "X"), ACTOR),
          HttpStatus.UNPROCESSABLE_CONTENT);
      assertThat(service.list(false, ACTOR))
          .extracting(CategoryResponse::code)
          .doesNotContain("STREAMING", "WS_NETFLIX");
    }

    @Test
    void activatingUnderAnInactiveAncestorAsksForTheAncestorFirst() {
      override(leisure, null, null, false);
      Category streaming = shared("STREAMING", "Streaming", "Streaming", leisure);

      assertStatus(
          () -> service.activate(streaming.getId(), ACTOR), HttpStatus.UNPROCESSABLE_CONTENT);

      CategoryResponse reactivated = service.activate(leisure.getId(), ACTOR);
      assertThat(reactivated.active()).isTrue();
      assertThat(service.get(streaming.getId(), ACTOR).active())
          .as("its own flag was never cleared")
          .isTrue();
    }
  }

  @Nested
  class BatchAssignability {

    @Test
    void manyCategoriesAreCheckedAgainstOneLoadOfTheTaxonomy() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);

      service.requireAssignable(
          List.of(hobby.getId(), leisure.getId(), hobby.getId(), uncategorized.getId()), WORKSPACE);

      verify(categoryRepository, times(1)).findVisibleTo(WORKSPACE);
      verify(overrideRepository, times(1)).findByWorkspaceId(WORKSPACE);
    }

    @Test
    void oneInactiveOrUnknownCategoryFailsTheBatch() {
      Category hobby = own("WS_HOBBY", "Hobby", "Hobby", null);
      override(leisure, null, null, false);

      assertStatus(
          () -> service.requireAssignable(List.of(hobby.getId(), leisure.getId()), WORKSPACE),
          HttpStatus.UNPROCESSABLE_CONTENT);
      assertStatus(
          () -> service.requireAssignable(List.of(hobby.getId(), UUID.randomUUID()), WORKSPACE),
          HttpStatus.NOT_FOUND);
    }

    @Test
    void anEmptyBatchLoadsNothing() {
      service.requireAssignable(List.of(), WORKSPACE);

      verify(categoryRepository, never()).findVisibleTo(any());
    }
  }

  @Nested
  class Capability {

    @Test
    void aReadOnlyMemberSeesCanEditFalse() {
      when(accessControlService.workspaceAccessLevel(MEMBER, WORKSPACE))
          .thenReturn(AccessLevelValues.READ);

      assertThat(service.list(false, ACTOR))
          .extracting(CategoryResponse::canEdit)
          .containsOnly(false);
      assertThat(service.get(leisure.getId(), ACTOR).canEdit()).isFalse();
    }

    @Test
    void anEditorSeesCanEditTrue() {
      when(accessControlService.workspaceAccessLevel(MEMBER, WORKSPACE))
          .thenReturn(AccessLevelValues.FULL);

      assertThat(service.list(false, ACTOR))
          .extracting(CategoryResponse::canEdit)
          .containsOnly(true);
      assertThat(
              service.create(new CreateCategoryRequest(null, "Pets", "Haustiere"), ACTOR).canEdit())
          .isTrue();
    }
  }

  // --- fixtures ------------------------------------------------------------------------------

  private Category shared(String code, String nameEn, String nameDe, Category parent) {
    return add(null, code, nameEn, nameDe, parent, true);
  }

  private Category own(String code, String nameEn, String nameDe, Category parent) {
    return add(WORKSPACE, code, nameEn, nameDe, parent, false);
  }

  private Category add(
      UUID workspaceId,
      String code,
      String nameEn,
      String nameDe,
      Category parent,
      boolean systemDefault) {
    Category category = new Category();
    ReflectionTestUtils.setField(category, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(category, "systemDefault", systemDefault);
    category.setWorkspaceId(workspaceId);
    category.setCode(code);
    category.setNameEn(nameEn);
    category.setNameDe(nameDe);
    category.setParentCategoryId(parent == null ? null : parent.getId());
    categories.add(category);
    return category;
  }

  private WorkspaceCategoryOverride override(
      Category category, String nameEn, String nameDe, Boolean active) {
    WorkspaceCategoryOverride override = new WorkspaceCategoryOverride(WORKSPACE, category.getId());
    ReflectionTestUtils.setField(override, "id", UUID.randomUUID());
    override.setNameEn(nameEn);
    override.setNameDe(nameDe);
    override.setActive(active);
    overrides.add(override);
    return override;
  }

  private static <T> T persist(T entity, List<T> table) {
    if (ReflectionTestUtils.getField(entity, "id") == null) {
      ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
    }
    if (!table.contains(entity)) {
      table.add(entity);
    }
    return entity;
  }

  private static void assertStatus(Runnable call, HttpStatus expected) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(expected));
  }
}
