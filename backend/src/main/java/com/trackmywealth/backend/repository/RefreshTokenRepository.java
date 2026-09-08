package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.RefreshToken;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  // US-02-01 disable (FR-AUT-005): every one of the disabled user's still-live refresh tokens
  // must stop working, not just future access-token issuance - a bulk update, not fetch-then-loop,
  // since only revoked_at changes and no entity-level business logic needs to run per row.
  @Modifying
  @Query(
      "UPDATE RefreshToken r SET r.revokedAt = :revokedAt "
          + "WHERE r.user.id = :userId AND r.revokedAt IS NULL")
  int revokeAllActiveTokensForUser(
      @Param("userId") UUID userId, @Param("revokedAt") OffsetDateTime revokedAt);

  // US-02-02 refresh rotation: the incoming plaintext token is hashed and looked up by that hash
  // - the plaintext itself is never persisted or queryable.
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  // US-02-02 FR-AUT-004 theft detection: reuse of an already-rotated-away token invalidates every
  // token in its family, not just the reused one - a bulk update across the whole family rather
  // than fetch-then-loop, since sibling tokens (already revoked or not) never need to be loaded as
  // entities just to flip theft_suspected. revoked_at is only set where still NULL so an
  // already-revoked sibling's original revocation timestamp is preserved.
  @Modifying
  @Query(
      "UPDATE RefreshToken r SET r.theftSuspected = true, "
          + "r.revokedAt = COALESCE(r.revokedAt, :revokedAt) WHERE r.familyId = :familyId")
  int markFamilyAsTheftSuspected(
      @Param("familyId") UUID familyId, @Param("revokedAt") OffsetDateTime revokedAt);
}
