package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AdminAuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {}
