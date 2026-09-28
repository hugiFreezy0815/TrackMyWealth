package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CategoryResponse;
import com.trackmywealth.backend.dto.CreateCategoryRequest;
import com.trackmywealth.backend.dto.UpdateCategoryRequest;
import com.trackmywealth.backend.entity.Category;
import com.trackmywealth.backend.entity.WorkspaceCategoryOverride;
import com.trackmywealth.backend.repository.CategoryRepository;
import com.trackmywealth.backend.repository.WorkspaceCategoryOverrideRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-08-04/FR-CAT-001..003/008: a workspace's reporting taxonomy is the shipped defaults plus its
 * own categories, up to three levels deep.
 *
 * <p>Shipped defaults are shared by every workspace and never edited by one (V20's RLS rejects the
 * write anyway): a workspace relabels or deactivates a default through a {@link
 * WorkspaceCategoryOverride}, and a default keeps its shipped position in the tree. Workspace
 * categories are edited directly and may be moved anywhere, including under a default.
 *
 * <p>Rules this service owns, since the schema cannot express them safely (V34):
 *
 * <ul>
 *   <li>Depth: at most {@value #MAX_DEPTH} levels, checked on create and on move (the moved
 *       subtree's height included); a move under its own subtree is rejected.
 *   <li>Codes are generated, {@code WS_}-prefixed and immutable, so reports keyed on them survive
 *       any relabelling (FR-CAT-008) and never clash with a default code.
 *   <li>Deactivation cascades to every subcategory; reactivation needs an active parent.
 *   <li>{@link #PROTECTED_CODES} can never be deactivated, deleted or given subcategories.
 *   <li>Hard delete (FR-LIF-001): never for a default (another workspace may rely on it), and for a
 *       workspace category only if nothing ever referred to it - otherwise deactivate.
 *   <li>EN and DE labels are each unique, ignoring case, among a category's siblings.
 * </ul>
 *
 * <p>Reads need workspace membership; changes need EDIT on the workspace, because the taxonomy is
 * shared by every member and reshapes everyone's reports.
 */
@Service
public class CategoryService {

  static final int MAX_DEPTH = 3;
  static final Set<String> PROTECTED_CODES = Set.of("UNCATEGORIZED", "TRANSFER_INTERNAL");
  static final String WORKSPACE_CODE_PREFIX = "WS_";
  private static final int CODE_BASE_MAX_LENGTH = 40;
  private static final String NOT_FOUND = "Category not found.";

  private final CategoryRepository categoryRepository;
  private final WorkspaceCategoryOverrideRepository overrideRepository;
  private final AccessControlService accessControlService;

  public CategoryService(
      CategoryRepository categoryRepository,
      WorkspaceCategoryOverrideRepository overrideRepository,
      AccessControlService accessControlService) {
    this.categoryRepository = categoryRepository;
    this.overrideRepository = overrideRepository;
    this.accessControlService = accessControlService;
  }

  /** The taxonomy in tree order (parents first, siblings by English label). */
  @Transactional(readOnly = true)
  public List<CategoryResponse> list(boolean includeInactive, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    Map<UUID, Category> categories = loadCategories(actor.workspaceId());
    Map<UUID, WorkspaceCategoryOverride> overrides = loadOverrides(actor.workspaceId());
    Map<UUID, List<Category>> children = childrenOf(categories, overrides);

    List<CategoryResponse> result = new ArrayList<>();
    for (Category root : children.getOrDefault(null, List.of())) {
      appendInTreeOrder(root, children, categories, overrides, includeInactive, result);
    }
    return result;
  }

  @Transactional(readOnly = true)
  public CategoryResponse get(UUID id, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    Map<UUID, Category> categories = loadCategories(actor.workspaceId());
    Map<UUID, WorkspaceCategoryOverride> overrides = loadOverrides(actor.workspaceId());
    return toResponse(require(categories, id), categories, overrides);
  }

  @Transactional
  public CategoryResponse create(CreateCategoryRequest request, AuthenticatedUserPrincipal actor) {
    UUID workspaceId = requireEditor(actor);
    Map<UUID, Category> categories = loadCategories(workspaceId);
    Map<UUID, WorkspaceCategoryOverride> overrides = loadOverrides(workspaceId);

    if (request.parentId() != null) {
      Category parent = require(categories, request.parentId());
      requireMayHoldChildren(parent, overrides);
      if (level(parent, categories) + 1 > MAX_DEPTH) {
        throw unprocessable(
            "Categories can be nested at most " + MAX_DEPTH + " levels deep (FR-CAT-001).");
      }
    }
    requireUniqueAmongSiblings(
        request.parentId(), null, request.nameEn(), request.nameDe(), categories, overrides);

    Set<String> takenCodes =
        categories.values().stream()
            .filter(category -> !category.isShared())
            .map(Category::getCode)
            .collect(Collectors.toSet());
    Category category = new Category();
    category.setWorkspaceId(workspaceId);
    category.setParentCategoryId(request.parentId());
    category.setCode(workspaceCodeFor(request.nameEn(), takenCodes));
    category.setNameEn(request.nameEn());
    category.setNameDe(request.nameDe());
    Category saved = categoryRepository.saveAndFlush(category);
    categories.put(saved.getId(), saved);
    return toResponse(saved, categories, overrides);
  }

  /**
   * Relabels and/or moves a category. A default is relabelled through this workspace's override (a
   * label equal to the shipped one is stored as "inherit") and cannot be moved.
   */
  @Transactional
  public CategoryResponse update(
      UUID id, UpdateCategoryRequest request, AuthenticatedUserPrincipal actor) {
    UUID workspaceId = requireEditor(actor);
    Map<UUID, Category> categories = loadCategories(workspaceId);
    Map<UUID, WorkspaceCategoryOverride> overrides = loadOverrides(workspaceId);
    Category category = require(categories, id);

    boolean moving = !Objects.equals(request.parentId(), category.getParentCategoryId());
    if (moving) {
      requireMovable(category, request.parentId(), categories, overrides);
    }
    requireUniqueAmongSiblings(
        request.parentId(), id, request.nameEn(), request.nameDe(), categories, overrides);

    if (category.isShared()) {
      WorkspaceCategoryOverride override = overrideFor(category, workspaceId, overrides);
      override.setNameEn(request.nameEn().equals(category.getNameEn()) ? null : request.nameEn());
      override.setNameDe(request.nameDe().equals(category.getNameDe()) ? null : request.nameDe());
      saveOrRemove(override, overrides);
    } else {
      category.setParentCategoryId(request.parentId());
      category.setNameEn(request.nameEn());
      category.setNameDe(request.nameDe());
      categoryRepository.saveAndFlush(category);
    }
    return toResponse(category, categories, overrides);
  }

  /**
   * Deactivates the category and every subcategory below it; historical assignments are kept
   * (FR-LIF-001). Idempotent: an already inactive category is returned unchanged.
   */
  @Transactional
  public CategoryResponse deactivate(UUID id, AuthenticatedUserPrincipal actor) {
    UUID workspaceId = requireEditor(actor);
    Map<UUID, Category> categories = loadCategories(workspaceId);
    Map<UUID, WorkspaceCategoryOverride> overrides = loadOverrides(workspaceId);
    Category category = require(categories, id);
    if (isProtected(category)) {
      throw unprocessable(
          "'" + category.getCode() + "' is required by the application and cannot be deactivated.");
    }

    Map<UUID, List<Category>> children = childrenOf(categories, overrides);
    List<Category> subtree = new ArrayList<>();
    collectSubtree(category, children, subtree);
    for (Category member : subtree) {
      if (isActive(member, overrides)) {
        setActive(member, false, workspaceId, overrides);
      }
    }
    return toResponse(category, categories, overrides);
  }

  /**
   * Reactivates this category only - subcategories deactivated with it stay inactive until
   * reactivated one by one. Requires an active parent. Idempotent.
   */
  @Transactional
  public CategoryResponse activate(UUID id, AuthenticatedUserPrincipal actor) {
    UUID workspaceId = requireEditor(actor);
    Map<UUID, Category> categories = loadCategories(workspaceId);
    Map<UUID, WorkspaceCategoryOverride> overrides = loadOverrides(workspaceId);
    Category category = require(categories, id);
    if (!isActive(category, overrides)) {
      Category parent = categories.get(category.getParentCategoryId());
      if (parent != null && !isActive(parent, overrides)) {
        throw unprocessable("Reactivate the parent category first.");
      }
      setActive(category, true, workspaceId, overrides);
    }
    return toResponse(category, categories, overrides);
  }

  /**
   * FR-LIF-001: a hard delete only for a workspace category nothing has ever referred to; anything
   * else is a 409 pointing at deactivation, which keeps historical assignments intact.
   */
  @Transactional
  public void delete(UUID id, AuthenticatedUserPrincipal actor) {
    UUID workspaceId = requireEditor(actor);
    Category category = require(loadCategories(workspaceId), id);
    if (category.isShared()) {
      throw conflict(
          "A default category cannot be deleted because other data may depend on it. Deactivate it"
              + " instead (FR-LIF-001).");
    }
    if (categoryRepository.isReferenced(id)) {
      throw conflict(
          "This category is in use by transactions, rules, budgets or subcategories. Deactivate it"
              + " instead; historical assignments are kept (FR-LIF-001).");
    }
    categoryRepository.delete(category);
    categoryRepository.flush();
  }

  /**
   * The check every assignment of a category (a rule, a manual or automatic categorization) must
   * pass: the category is visible to the workspace and active. An inactive one is a 422; historical
   * assignments to it are untouched.
   */
  @Transactional(readOnly = true)
  public void requireAssignable(UUID categoryId, UUID workspaceId) {
    Map<UUID, Category> categories = loadCategories(workspaceId);
    Category category = require(categories, categoryId);
    if (!isActive(category, loadOverrides(workspaceId))) {
      throw unprocessable("An inactive category cannot be assigned. Reactivate it first.");
    }
  }

  /**
   * The stable code for a new workspace category: {@code WS_} plus the English label as upper-case
   * ASCII (accents stripped, other characters folded to single underscores), with a numeric suffix
   * when the workspace already uses it. Defaults never start with {@code WS_} (V34), so the two can
   * never clash.
   */
  static String workspaceCodeFor(String nameEn, Set<String> takenCodes) {
    String base =
        Normalizer.normalize(nameEn, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
    if (base.length() > CODE_BASE_MAX_LENGTH) {
      base = base.substring(0, CODE_BASE_MAX_LENGTH).replaceAll("_+$", "");
    }
    if (base.isEmpty()) {
      base = "CATEGORY";
    }
    String code = WORKSPACE_CODE_PREFIX + base;
    for (int suffix = 2; takenCodes.contains(code); suffix++) {
      code = WORKSPACE_CODE_PREFIX + base + "_" + suffix;
    }
    return code;
  }

  // --- rules ---------------------------------------------------------------------------------

  private UUID requireEditor(AuthenticatedUserPrincipal actor) {
    UUID memberId = accessControlService.requireActingMember(actor);
    accessControlService.requireWorkspaceAccess(
        memberId, actor.workspaceId(), AccessLevelValues.EDIT);
    return actor.workspaceId();
  }

  private void requireMayHoldChildren(
      Category parent, Map<UUID, WorkspaceCategoryOverride> overrides) {
    if (isProtected(parent)) {
      throw unprocessable(
          "'"
              + parent.getCode()
              + "' is required by the application and cannot have subcategories.");
    }
    if (!isActive(parent, overrides)) {
      throw unprocessable("A category cannot be placed under an inactive category.");
    }
  }

  private void requireMovable(
      Category category,
      UUID newParentId,
      Map<UUID, Category> categories,
      Map<UUID, WorkspaceCategoryOverride> overrides) {
    if (category.isShared()) {
      throw unprocessable(
          "A default category keeps its shipped position; only your own categories can be moved.");
    }
    if (newParentId == null) {
      return;
    }
    Category newParent = require(categories, newParentId);
    if (isSelfOrDescendant(newParent, category.getId(), categories)) {
      throw unprocessable("A category cannot be moved under itself or one of its subcategories.");
    }
    requireMayHoldChildren(newParent, overrides);
    int subtreeHeight = height(category, childrenOf(categories, overrides));
    if (level(newParent, categories) + subtreeHeight > MAX_DEPTH) {
      throw unprocessable(
          "Categories can be nested at most "
              + MAX_DEPTH
              + " levels deep (FR-CAT-001); this move would exceed that.");
    }
  }

  private void requireUniqueAmongSiblings(
      UUID parentId,
      UUID excludedId,
      String nameEn,
      String nameDe,
      Map<UUID, Category> categories,
      Map<UUID, WorkspaceCategoryOverride> overrides) {
    for (Category sibling : categories.values()) {
      if (sibling.getId().equals(excludedId)
          || !Objects.equals(sibling.getParentCategoryId(), parentId)) {
        continue;
      }
      if (sameLabel(nameEn(sibling, overrides), nameEn)
          || sameLabel(nameDe(sibling, overrides), nameDe)) {
        throw conflict(
            "A category with this name already exists at this level (it may be inactive:"
                + " reactivate it instead).");
      }
    }
  }

  private static boolean sameLabel(String a, String b) {
    return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
  }

  private static boolean isProtected(Category category) {
    return category.isShared() && PROTECTED_CODES.contains(category.getCode());
  }

  // --- tree ----------------------------------------------------------------------------------

  private static int level(Category category, Map<UUID, Category> categories) {
    int level = 1;
    // A top-level category's parent id is null, and Map.get(null) is null. Bounded by the tree
    // size, so a corrupt cycle in the data can never loop forever.
    Category parent = categories.get(category.getParentCategoryId());
    while (parent != null && level <= categories.size()) {
      level++;
      parent = categories.get(parent.getParentCategoryId());
    }
    return level;
  }

  private static int height(Category category, Map<UUID, List<Category>> children) {
    int tallestChild = 0;
    for (Category child : children.getOrDefault(category.getId(), List.of())) {
      tallestChild = Math.max(tallestChild, height(child, children));
    }
    return 1 + tallestChild;
  }

  private static boolean isSelfOrDescendant(
      Category candidate, UUID ancestorId, Map<UUID, Category> categories) {
    Category current = candidate;
    for (int steps = 0; current != null && steps <= categories.size(); steps++) {
      if (current.getId().equals(ancestorId)) {
        return true;
      }
      current = categories.get(current.getParentCategoryId());
    }
    return false;
  }

  private static void collectSubtree(
      Category category, Map<UUID, List<Category>> children, List<Category> into) {
    into.add(category);
    for (Category child : children.getOrDefault(category.getId(), List.of())) {
      collectSubtree(child, children, into);
    }
  }

  // Keyed by parent id (null for the top level), siblings sorted by effective English label.
  private static Map<UUID, List<Category>> childrenOf(
      Map<UUID, Category> categories, Map<UUID, WorkspaceCategoryOverride> overrides) {
    Map<UUID, List<Category>> children = new HashMap<>();
    for (Category category : categories.values()) {
      children
          .computeIfAbsent(category.getParentCategoryId(), key -> new ArrayList<>())
          .add(category);
    }
    Comparator<Category> byLabel =
        Comparator.comparing(
            (Category category) -> nameEn(category, overrides), String.CASE_INSENSITIVE_ORDER);
    children.values().forEach(siblings -> siblings.sort(byLabel));
    return children;
  }

  private void appendInTreeOrder(
      Category category,
      Map<UUID, List<Category>> children,
      Map<UUID, Category> categories,
      Map<UUID, WorkspaceCategoryOverride> overrides,
      boolean includeInactive,
      List<CategoryResponse> into) {
    if (!includeInactive && !isActive(category, overrides)) {
      return; // deactivation cascades, so nothing below an inactive category is active either
    }
    into.add(toResponse(category, categories, overrides));
    for (Category child : children.getOrDefault(category.getId(), List.of())) {
      appendInTreeOrder(child, children, categories, overrides, includeInactive, into);
    }
  }

  // --- effective values (a default's with this workspace's override applied) ------------------

  private static String nameEn(Category category, Map<UUID, WorkspaceCategoryOverride> overrides) {
    WorkspaceCategoryOverride override = overrides.get(category.getId());
    return override != null && override.getNameEn() != null
        ? override.getNameEn()
        : category.getNameEn();
  }

  private static String nameDe(Category category, Map<UUID, WorkspaceCategoryOverride> overrides) {
    WorkspaceCategoryOverride override = overrides.get(category.getId());
    return override != null && override.getNameDe() != null
        ? override.getNameDe()
        : category.getNameDe();
  }

  private static boolean isActive(
      Category category, Map<UUID, WorkspaceCategoryOverride> overrides) {
    WorkspaceCategoryOverride override = overrides.get(category.getId());
    return override != null && override.getActive() != null
        ? override.getActive()
        : category.isActive();
  }

  private void setActive(
      Category category,
      boolean active,
      UUID workspaceId,
      Map<UUID, WorkspaceCategoryOverride> overrides) {
    if (category.isShared()) {
      WorkspaceCategoryOverride override = overrideFor(category, workspaceId, overrides);
      override.setActive(active == category.isActive() ? null : active);
      saveOrRemove(override, overrides);
    } else {
      category.setActive(active);
      categoryRepository.saveAndFlush(category);
    }
  }

  private static WorkspaceCategoryOverride overrideFor(
      Category category, UUID workspaceId, Map<UUID, WorkspaceCategoryOverride> overrides) {
    WorkspaceCategoryOverride override = overrides.get(category.getId());
    return override != null
        ? override
        : new WorkspaceCategoryOverride(workspaceId, category.getId());
  }

  // V34 rejects an override that overrides nothing, so one back to "inherit everything" is removed.
  private void saveOrRemove(
      WorkspaceCategoryOverride override, Map<UUID, WorkspaceCategoryOverride> overrides) {
    if (override.isEmpty()) {
      if (override.getId() != null) {
        overrideRepository.delete(override);
        overrideRepository.flush();
      }
      overrides.remove(override.getCategoryId());
    } else {
      overrides.put(override.getCategoryId(), overrideRepository.saveAndFlush(override));
    }
  }

  // --- loading and mapping -------------------------------------------------------------------

  private Map<UUID, Category> loadCategories(UUID workspaceId) {
    Map<UUID, Category> categories = new LinkedHashMap<>();
    for (Category category : categoryRepository.findVisibleTo(workspaceId)) {
      categories.put(category.getId(), category);
    }
    return categories;
  }

  private Map<UUID, WorkspaceCategoryOverride> loadOverrides(UUID workspaceId) {
    Map<UUID, WorkspaceCategoryOverride> overrides = new HashMap<>();
    for (WorkspaceCategoryOverride override : overrideRepository.findByWorkspaceId(workspaceId)) {
      overrides.put(override.getCategoryId(), override);
    }
    return overrides;
  }

  private static Category require(Map<UUID, Category> categories, UUID id) {
    Category category = categories.get(id);
    if (category == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }
    return category;
  }

  private static CategoryResponse toResponse(
      Category category,
      Map<UUID, Category> categories,
      Map<UUID, WorkspaceCategoryOverride> overrides) {
    return new CategoryResponse(
        category.getId(),
        category.getParentCategoryId(),
        category.getCode(),
        nameEn(category, overrides),
        nameDe(category, overrides),
        level(category, categories),
        isActive(category, overrides),
        category.isShared(),
        isProtected(category),
        category.isShared() && overrides.containsKey(category.getId()));
  }

  private static ResponseStatusException unprocessable(String detail) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, detail);
  }

  private static ResponseStatusException conflict(String detail) {
    return new ResponseStatusException(HttpStatus.CONFLICT, detail);
  }
}
