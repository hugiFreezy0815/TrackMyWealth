package com.trackmywealth.backend.entity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * US-04-10 (#484): one row of {@code InstitutionCatalogueRepository.searchCatalogue} - a catalogue
 * entry with its import formats aggregated in the same statement, so no template is loaded as an
 * entity. A scalar read model of the catalogue, not an API type: it lives with the entities so the
 * architecture rules keep it behind the service, which maps it to the response.
 */
public interface InstitutionCatalogueListingRow {

  UUID getId();

  String getName();

  String getCountry();

  String getInstitutionType();

  @Nullable String getIdentifier();

  @Nullable String getLogoUrl();

  /** Distinct, alphabetical, comma-joined API format names; an empty string when there are none. */
  String getImportFormats();
}
