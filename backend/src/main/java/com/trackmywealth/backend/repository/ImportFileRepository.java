package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.ImportFile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** US-07-04: an import batch's original file, keyed by the batch. */
@Repository
public interface ImportFileRepository extends JpaRepository<ImportFile, UUID> {}
