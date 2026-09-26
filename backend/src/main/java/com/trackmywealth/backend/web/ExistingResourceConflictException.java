package com.trackmywealth.backend.web;

import java.util.UUID;

/**
 * A create that collides with a resource the caller can already see, answered with a 409 that names
 * the existing resource's id (under {@code propertyName}) so a client can offer to update it
 * instead of failing silently - US-25-01's "update today's snapshot" is the first such case.
 *
 * <p>A plain {@code ResponseStatusException} cannot do this: it is rendered by Spring Boot's error
 * page, which drops any extra property. {@link GlobalExceptionHandler} renders this one as a {@code
 * ProblemDetail} directly.
 */
public class ExistingResourceConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String propertyName;
  private final UUID existingId;

  public ExistingResourceConflictException(String detail, String propertyName, UUID existingId) {
    super(detail);
    this.propertyName = propertyName;
    this.existingId = existingId;
  }

  public String getPropertyName() {
    return propertyName;
  }

  public UUID getExistingId() {
    return existingId;
  }
}
