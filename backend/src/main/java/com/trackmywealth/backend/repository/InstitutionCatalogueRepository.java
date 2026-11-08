package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.InstitutionCatalogue;
import com.trackmywealth.backend.entity.InstitutionCatalogueListingRow;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface InstitutionCatalogueRepository extends JpaRepository<InstitutionCatalogue, UUID> {

  /**
   * US-04-01/US-04-10: a page of active catalogue entries with their import formats, read in one
   * statement (plus the count) rather than one template query per entry.
   *
   * <p>{@code namePattern} is a complete {@code ILIKE} pattern whose wildcards the caller has
   * already escaped with PostgreSQL's default escape character, the backslash; {@code countries} is
   * a comma-joined list of upper-case ISO 3166-1 alpha-2 codes. Either is {@code null} for no
   * filter.
   *
   * <p>Only shared templates ({@code workspace_id IS NULL}) count: the catalogue is the same for
   * every caller, and without that condition RLS would also let in the caller's own workspace
   * templates. {@code import_template.file_format} has no CAMT value (V99's CHECK); CAMT comes from
   * the catalogue's {@code CAMT053} import method. A new file format must be mapped here too, or it
   * is left out of the list.
   */
  @Query(
      value =
          """
          SELECT c.id AS id,
                 c.name AS name,
                 c.country AS country,
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
            AND (CAST(:namePattern AS TEXT) IS NULL OR c.name ILIKE :namePattern)
            AND (CAST(:countries AS TEXT) IS NULL
                 OR c.country = ANY(string_to_array(:countries, ',')))
          """,
      countQuery =
          """
          SELECT COUNT(*)
          FROM institution_catalogue c
          WHERE c.is_active
            AND (CAST(:namePattern AS TEXT) IS NULL OR c.name ILIKE :namePattern)
            AND (CAST(:countries AS TEXT) IS NULL
                 OR c.country = ANY(string_to_array(:countries, ',')))
          """,
      nativeQuery = true)
  Page<InstitutionCatalogueListingRow> searchCatalogue(
      @Param("namePattern") @Nullable String namePattern,
      @Param("countries") @Nullable String countries,
      Pageable pageable);
}
