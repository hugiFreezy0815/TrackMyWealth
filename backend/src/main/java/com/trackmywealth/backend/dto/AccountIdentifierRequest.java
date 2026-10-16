package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * US-07-10: the account's identifier, sent once to register it. The server keeps only its keyed
 * hash and a masked display value; no response repeats it.
 */
public record AccountIdentifierRequest(
    @Schema(
            description =
                "An IBAN; for a credit card account, its card number masked except for at most the"
                    + " first 6 and the last 4 digits.")
        @NotBlank
        @Size(max = 64)
        String identifier,
    @Schema(description = "Optional CONTRACT_NUMBER kind; otherwise use the account's IBAN or MASKED_CARD.")
        String kind) {

  public AccountIdentifierRequest(String identifier) {
    this(identifier, null);
  }

  // The plain account number must never reach a log line, should anything print the request.
  @Override
  public String toString() {
    return "AccountIdentifierRequest[identifier=(hidden)]";
  }
}
