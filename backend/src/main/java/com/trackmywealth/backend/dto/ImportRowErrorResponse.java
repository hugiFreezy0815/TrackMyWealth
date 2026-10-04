package com.trackmywealth.backend.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Why one row could not be parsed: a stable {@code code} from {@link ImportRowErrorValues}, its
 * {@code args}, and the {@code message} built from both in the caller's language (EN/DE, #153).
 */
public record ImportRowErrorResponse(String code, Map<String, String> args, String message) {

  public ImportRowErrorResponse {
    args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
  }
}
