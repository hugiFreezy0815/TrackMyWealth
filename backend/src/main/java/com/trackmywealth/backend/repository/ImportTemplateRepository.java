package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.ImportTemplate;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportTemplateRepository extends JpaRepository<ImportTemplate, UUID> {

  /**
   * The current version of every shipped template and of the workspace's own. Filtered explicitly
   * as well as by RLS (V20), so the result never depends on the database role bypassing row-level
   * security.
   */
  @Query(
      "SELECT t FROM ImportTemplate t WHERE t.current = true"
          + " AND (t.workspaceId IS NULL OR t.workspaceId = :workspaceId)"
          + " ORDER BY t.name, t.id")
  List<ImportTemplate> findCurrentVisibleTo(@Param("workspaceId") UUID workspaceId);

  /** One version, if the workspace may see it (its own or a shipped one). */
  @Query(
      "SELECT t FROM ImportTemplate t WHERE t.id = :id"
          + " AND (t.workspaceId IS NULL OR t.workspaceId = :workspaceId)")
  Optional<ImportTemplate> findVisibleTo(
      @Param("id") UUID id, @Param("workspaceId") UUID workspaceId);

  /**
   * {@link #findVisibleTo}, locking the row: every change of a template starts here, so two
   * concurrent changes of one template run one after the other and the second sees the first's
   * result (e.g. a delete cannot miss a version a concurrent edit just wrote).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT t FROM ImportTemplate t WHERE t.id = :id"
          + " AND (t.workspaceId IS NULL OR t.workspaceId = :workspaceId)")
  Optional<ImportTemplate> findVisibleToForUpdate(
      @Param("id") UUID id, @Param("workspaceId") UUID workspaceId);

  /** The current version of a template family, if any. */
  Optional<ImportTemplate> findByTemplateFamilyIdAndCurrentTrue(UUID templateFamilyId);

  /** FR-LIF-001: whether any import batch used any version of the template. */
  @Query(
      value =
          "SELECT EXISTS (SELECT 1 FROM import_batch b JOIN import_template t"
              + " ON t.id = b.template_id WHERE t.template_family_id = :familyId)",
      nativeQuery = true)
  boolean isFamilyUsed(@Param("familyId") UUID familyId);

  /** Hard-deletes every version of a template; only for a family no batch used. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("DELETE FROM ImportTemplate t WHERE t.templateFamilyId = :familyId")
  int deleteFamily(@Param("familyId") UUID familyId);
}
