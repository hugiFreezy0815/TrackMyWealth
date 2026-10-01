package com.trackmywealth.backend.service;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * FR-CNC-001/002 optimistic-concurrency precondition shared by mutable resources.
 *
 * <p>Callers must invoke this only after the resource has been resolved and authorization has
 * succeeded. That ordering keeps stale-version information from revealing whether a foreign row
 * exists. JPA's {@code @Version} remains the second line of defense against a race that occurs
 * after this explicit client-read precondition check but before flush.
 */
@Service
public class VersionPreconditionService {

  public void requireCurrent(
      Integer expectedVersion, Integer currentVersion, String resourceName) {
    if (currentVersion == null) {
      throw new IllegalStateException(resourceName + " has no persistence version.");
    }
    if (expectedVersion == null) {
      throw new ApiException(
          HttpStatus.PRECONDITION_REQUIRED,
          ApiErrorCode.VERSION_REQUIRED,
          "This update requires If-Match with the version you last read.");
    }
    if (!expectedVersion.equals(currentVersion)) {
      throw new ApiException(
          HttpStatus.PRECONDITION_FAILED,
          ApiErrorCode.VERSION_CONFLICT,
          "This "
              + resourceName
              + " was changed after you read it. Reload the current state and retry explicitly.");
    }
  }
}
