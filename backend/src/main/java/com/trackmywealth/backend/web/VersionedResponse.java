package com.trackmywealth.backend.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Small HTTP-boundary helper for ADR 0004's body + strong ETag response convention. */
public final class VersionedResponse {

  private VersionedResponse() {}

  public static <T> ResponseEntity<T> ok(T body, int version) {
    return ResponseEntity.ok().eTag(IfMatchVersionParser.toEtag(version)).body(body);
  }

  public static <T> ResponseEntity<T> created(T body, int version) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .eTag(IfMatchVersionParser.toEtag(version))
        .body(body);
  }
}
