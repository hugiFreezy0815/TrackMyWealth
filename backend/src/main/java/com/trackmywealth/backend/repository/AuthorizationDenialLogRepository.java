package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AuthorizationDenialLog;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthorizationDenialLogRepository
    extends JpaRepository<AuthorizationDenialLog, UUID> {

  List<AuthorizationDenialLog> findByRequestedEntityId(UUID requestedEntityId);

  List<AuthorizationDenialLog> findByPrincipalUserIdOrderByOccurredAtAsc(UUID principalUserId);

  /**
   * Deletes at most {@code batchSize} rows older than {@code cutoff} (#205 retention), using V47's
   * {@code occurred_at} index; the caller repeats it until a batch comes back short.
   */
  @Modifying
  @Query(
      value =
          "DELETE FROM authorization_denial_log WHERE id IN (SELECT id FROM"
              + " authorization_denial_log WHERE occurred_at < :cutoff LIMIT :batchSize)",
      nativeQuery = true)
  int deleteBatchOccurredBefore(
      @Param("cutoff") OffsetDateTime cutoff, @Param("batchSize") int batchSize);
}
