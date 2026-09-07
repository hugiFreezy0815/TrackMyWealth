package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.RefreshToken;
import java.time.OffsetDateTime;
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
}
