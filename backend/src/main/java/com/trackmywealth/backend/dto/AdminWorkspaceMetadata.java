package com.trackmywealth.backend.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The columns exposed by the administrator workspace metadata function. */
public interface AdminWorkspaceMetadata {
  UUID getId();

  @Nullable String getName();
}
