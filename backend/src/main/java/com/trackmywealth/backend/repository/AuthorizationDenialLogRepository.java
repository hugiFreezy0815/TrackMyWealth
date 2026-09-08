package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AuthorizationDenialLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthorizationDenialLogRepository
    extends JpaRepository<AuthorizationDenialLog, UUID> {

  List<AuthorizationDenialLog> findByRequestedEntityId(UUID requestedEntityId);
}
