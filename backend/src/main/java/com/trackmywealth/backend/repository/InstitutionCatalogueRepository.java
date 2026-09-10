package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.InstitutionCatalogue;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InstitutionCatalogueRepository extends JpaRepository<InstitutionCatalogue, UUID> {

  Page<InstitutionCatalogue> findByActiveTrueAndNameContainingIgnoreCase(
      String name, Pageable pageable);

  Page<InstitutionCatalogue> findByActiveTrue(Pageable pageable);
}
