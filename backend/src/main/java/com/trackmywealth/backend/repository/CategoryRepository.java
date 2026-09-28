package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.Category;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

  /**
   * The shipped defaults plus the workspace's own categories. Filtered explicitly as well as by RLS
   * (V20), so the result never depends on the database role bypassing row-level security.
   */
  @Query("SELECT c FROM Category c WHERE c.workspaceId IS NULL OR c.workspaceId = :workspaceId")
  List<Category> findVisibleTo(@Param("workspaceId") UUID workspaceId);

  /**
   * FR-LIF-001: a category may be hard-deleted only if nothing has ever referred to it - not a
   * transaction or split, rule, source mapping, budget line or categorization-log entry, and no
   * subcategory. The foreign keys stay the backstop for a reference this workspace cannot see.
   */
  @Query(
      value =
          "SELECT EXISTS (SELECT 1 FROM transaction WHERE category_id = :id)"
              + " OR EXISTS (SELECT 1 FROM transaction_category_split WHERE category_id = :id)"
              + " OR EXISTS (SELECT 1 FROM categorization_rule WHERE category_id = :id)"
              + " OR EXISTS (SELECT 1 FROM category_source_mapping WHERE category_id = :id)"
              + " OR EXISTS (SELECT 1 FROM budget_line WHERE category_id = :id)"
              + " OR EXISTS (SELECT 1 FROM transaction_categorization_log WHERE category_id = :id)"
              + " OR EXISTS (SELECT 1 FROM category WHERE parent_category_id = :id)",
      nativeQuery = true)
  boolean isReferenced(@Param("id") UUID id);

  /** A shipped default by its stable code (FR-CAT-008), e.g. {@code UNCATEGORIZED}. */
  Optional<Category> findByWorkspaceIdIsNullAndCode(String code);

  /** Several shipped defaults at once, e.g. UNCATEGORIZED and the type-implied FEES and TAXES. */
  List<Category> findByWorkspaceIdIsNullAndCodeIn(Collection<String> codes);

  /**
   * FR-CAT-005/010: the category the shipped mapping assigns to one source code, e.g. {@code
   * ("MCC", "5411")}. {@code category_source_mapping} is global configuration with at most one row
   * per code (V13's unique constraint).
   */
  @Query(
      value =
          "SELECT category_id FROM category_source_mapping"
              + " WHERE source_standard = :standard AND source_code = :code",
      nativeQuery = true)
  Optional<UUID> findMappedCategoryId(
      @Param("standard") String standard, @Param("code") String code);
}
