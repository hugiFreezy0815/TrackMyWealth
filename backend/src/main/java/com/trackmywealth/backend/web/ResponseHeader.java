package com.trackmywealth.backend.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * US-29-09 (#455): a response header the API contract documents for the handlers that send it
 * ({@link SendsHeaders}), and the success responses it comes with.
 */
public enum ResponseHeader {

  /**
   * The strong ETag of the returned resource's version ({@link VersionedResponse}), the value a
   * client sends back as {@code If-Match}. On every success response with a body.
   */
  ETAG(HttpHeaders.ETAG),

  /** The URL of the resource a request created. On the 201 only. */
  LOCATION(HttpHeaders.LOCATION);

  private final String wireName;

  ResponseHeader(String wireName) {
    this.wireName = wireName;
  }

  /** The header's name on the wire, as {@link HttpHeaders} spells it. */
  public String headerName() {
    return wireName;
  }

  /** Whether a success response with {@code status} carries this header. */
  public boolean comesWith(HttpStatus status) {
    return switch (this) {
      case ETAG -> status.is2xxSuccessful() && status != HttpStatus.NO_CONTENT;
      case LOCATION -> status == HttpStatus.CREATED;
    };
  }
}
