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

  @Modifying
  @Query("DELETE FROM AuthorizationDenialLog log WHERE log.occurredAt < :cutoff")
  int deleteOccurredBefore(@Param("cutoff") OffsetDateTime cutoff);
}
