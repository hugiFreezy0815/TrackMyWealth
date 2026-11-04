package com.trackmywealth.backend.web;

import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * US-29-09 (#455): a response header the API contract documents for the handlers that send it
 * ({@link SendsHeaders}), and the success responses it comes with.
 */
public enum ResponseHeader {

  /**
   * The strong ETag of the returned resource's version ({@link VersionedResponse}), the value a
   * client sends back as {@code If-Match}. On the 200 and the 201, the two statuses {@link
   * VersionedResponse} answers: a 202 or 206 carries no resource version, so the contract does not
   * claim one there.
   */
  ETAG(HttpHeaders.ETAG, Set.of(HttpStatus.OK, HttpStatus.CREATED)),

  /** The URL of the resource a request created. On the 201 only. */
  LOCATION(HttpHeaders.LOCATION, Set.of(HttpStatus.CREATED));

  private final String wireName;
  private final Set<HttpStatus> statuses;

  ResponseHeader(String wireName, Set<HttpStatus> statuses) {
    this.wireName = wireName;
    this.statuses = statuses;
  }

  /** The header's name on the wire, as {@link HttpHeaders} spells it. */
  public String headerName() {
    return wireName;
  }

  /** Whether a success response with {@code status} carries this header. */
  public boolean comesWith(HttpStatus status) {
    return statuses.contains(status);
  }
}
