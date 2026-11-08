package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.InstitutionCatalogue;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InstitutionCatalogueRepository extends JpaRepository<InstitutionCatalogue, UUID> {

  Page<InstitutionCatalogue> findByActiveTrueAndNameContainingIgnoreCase(
      String name, Pageable pageable);

  Page<InstitutionCatalogue> findByActiveTrue(Pageable pageable);

  /** The catalogue page and its import capabilities are read in one SQL statement, not N+1. */
  @Query(
      value =
          """
          SELECT c.id AS id,
                 c.name AS name,
                 TRIM(c.country) AS country,
                 c.institution_type AS "institutionType",
                 c.identifier AS identifier,
                 c.logo_url AS "logoUrl",
                 COALESCE(
                   (SELECT string_agg(DISTINCT formats.format, ',' ORDER BY formats.format)
                    FROM (
                      SELECT CASE WHEN t.file_format IN ('PDF_TEXT', 'PDF_OCR')
                                  THEN 'PDF' ELSE t.file_format END AS format
                      FROM import_template t
                      WHERE t.institution_catalogue_id = c.id
                        AND t.workspace_id IS NULL
                        AND t.is_current
                        AND t.is_active
                        AND t.file_format IN ('CSV', 'PDF_TEXT', 'PDF_OCR', 'SPREADSHEET')
                      UNION ALL
                      SELECT 'CAMT' WHERE jsonb_exists(c.available_import_methods, 'CAMT053')
                    ) formats),
                   '') AS "importFormats"
          FROM institution_catalogue c
          WHERE c.is_active
            AND (:query IS NULL OR c.name ILIKE CONCAT('%', :query, '%'))
            AND (:countries IS NULL OR TRIM(c.country) = ANY(string_to_array(:countries, ',')))
          """,
      countQuery =
          """
          SELECT COUNT(*)
          FROM institution_catalogue c
          WHERE c.is_active
            AND (:query IS NULL OR c.name ILIKE CONCAT('%', :query, '%'))
            AND (:countries IS NULL OR TRIM(c.country) = ANY(string_to_array(:countries, ',')))
          """,
      nativeQuery = true)
  Page<CatalogueListing> searchCatalogue(
      @Param("query") String query,
      @Param("countries") String countries,
      Pageable pageable);

  /** A scalar projection avoids loading templates as entities or exposing their internals. */
  interface CatalogueListing {
    UUID getId();

    String getName();

    String getCountry();

    String getInstitutionType();

    String getIdentifier();

    String getLogoUrl();

    /** Distinct, alphabetical CSV of API format names; empty string when unsupported. */
    String getImportFormats();
  }
}
