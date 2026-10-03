package com.trackmywealth.backend.dto;

/** Where the FX import interval in force comes from (US-06-07, #227). */
public enum FxImportIntervalSource {
  /** An administrator set it; it wins over the environment at every start. */
  ADMINISTRATOR,
  /** No administrator set one, so the deployment's {@code FX_IMPORT_CRON} applies. */
  ENVIRONMENT
}
