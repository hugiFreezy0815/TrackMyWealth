package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.SecurityFieldProvenance;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityFieldProvenanceRepository
    extends JpaRepository<SecurityFieldProvenance, UUID> {}
