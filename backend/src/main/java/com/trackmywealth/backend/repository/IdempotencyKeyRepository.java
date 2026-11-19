package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.IdempotencyKey;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * {@code idempotency_key} (V124, #574). Every statement is native: the claim must be one atomic
 * {@code INSERT ... ON CONFLICT DO NOTHING}, and the row is then read under {@code FOR UPDATE} so
 * two requests with the same key decide one after the other. Workspace RLS applies throughout.
 */
@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

  /** Claims the key as {@code IN_PROGRESS}; 0 when a row for this scope already exists. */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO idempotency_key
              (workspace_id, user_id, operation_id, idempotency_key, request_fingerprint, created_at,
               expires_at)
          VALUES (:workspaceId, :userId, :operation, :key, :fingerprint, :now, :expiresAt)
          ON CONFLICT ON CONSTRAINT idempotency_key_scope_unique DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("workspaceId") UUID workspaceId,
      @Param("userId") UUID userId,
      @Param("operation") String operation,
      @Param("key") String key,
      @Param("fingerprint") String fingerprint,
      @Param("now") OffsetDateTime now,
      @Param("expiresAt") OffsetDateTime expiresAt);

  @Query(
      value =
          """
          SELECT * FROM idempotency_key
          WHERE workspace_id = :workspaceId AND user_id = :userId AND operation_id = :operation
            AND idempotency_key = :key
          FOR UPDATE
          """,
      nativeQuery = true)
  Optional<IdempotencyKey> lockByScope(
      @Param("workspaceId") UUID workspaceId,
      @Param("userId") UUID userId,
      @Param("operation") String operation,
      @Param("key") String key);

  /** An expired or abandoned row becomes a fresh claim for a new request. */
  @Modifying
  @Query(
      value =
          """
          UPDATE idempotency_key
          SET request_fingerprint = :fingerprint, status = 'IN_PROGRESS', response_status = NULL,
              response_body = NULL, created_at = :now, expires_at = :expiresAt
          WHERE id = :id
          """,
      nativeQuery = true)
  int reclaim(
      @Param("id") UUID id,
      @Param("fingerprint") String fingerprint,
      @Param("now") OffsetDateTime now,
      @Param("expiresAt") OffsetDateTime expiresAt);

  @Modifying
  @Query(
      value =
          """
          UPDATE idempotency_key
          SET status = 'COMPLETED', response_status = :responseStatus, response_body = :body
          WHERE id = :id AND status = 'IN_PROGRESS'
          """,
      nativeQuery = true)
  int complete(
      @Param("id") UUID id,
      @Param("responseStatus") short responseStatus,
      @Param("body") String body);

  @Modifying
  @Query(
      value = "DELETE FROM idempotency_key WHERE id = :id AND status = 'IN_PROGRESS'",
      nativeQuery = true)
  int release(@Param("id") UUID id);

  /** V124's SECURITY DEFINER purge: at most {@code limit} expired rows of any workspace. */
  @Query(value = "SELECT purge_expired_idempotency_keys(:now, :limit)", nativeQuery = true)
  int purgeExpired(@Param("now") OffsetDateTime now, @Param("limit") int limit);
}
