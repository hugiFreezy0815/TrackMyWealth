package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.entity.ImportRowRaw;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the JSON an import row is stored with (US-07-04): its canonical values and its raw cells or
 * message arguments. Shared by the preview ({@link ImportBatchService}) and the re-check of its
 * duplicates ({@link ImportRecheckService}), which the preview depends on.
 */
@Service
public class ImportRowJsonService {

  private final ObjectMapper objectMapper;

  public ImportRowJsonService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** The canonical values a row was stored with, or {@code null} for a row that was unreadable. */
  public CanonicalImportRow canonical(ImportRowRaw row) {
    return row.getCanonicalData() == null
        ? null
        : objectMapper.readValue(row.getCanonicalData(), CanonicalImportRow.class);
  }

  /** A JSON object of strings, in its stored order (raw cells, message arguments). */
  public Map<String, String> stringMap(String json) {
    if (json == null) {
      return new LinkedHashMap<>();
    }
    JavaType type =
        objectMapper
            .getTypeFactory()
            .constructMapType(LinkedHashMap.class, String.class, String.class);
    return objectMapper.readValue(json, type);
  }
}
