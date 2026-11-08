package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record InstitutionCatalogueEntrySummaryResponse(
    UUID id,
    String name,
    String country,
    String institutionType,
    @Nullable String identifier,
    @Nullable String logoUrl,
    @ArraySchema(
            arraySchema =
                @Schema(
                    description =
                        "US-04-10: the file formats a statement of this institution can be"
                            + " imported in, sorted; empty when it has to be entered by hand."),
            schema = @Schema(allowableValues = {"CAMT", "CSV", "PDF", "SPREADSHEET"}),
            uniqueItems = true)
        List<String> importFormats) {

  public InstitutionCatalogueEntrySummaryResponse {
    importFormats = List.copyOf(importFormats);
  }
}
