package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * US-07-10: the account's identifier, sent once to register it. The server keeps only its keyed
 * hash and a masked display value; no response repeats it. Without {@code kind} it is the account's
 * own kind; {@code CONTRACT_NUMBER} registers a contract number besides it (#323).
 */
public record AccountIdentifierRequest(
    @Schema(
            description =
                "An IBAN; for a credit card account, its card number masked except for at most the"
                    + " first 6 and the last 4 digits.")
        @NotBlank
        @Size(max = 64)
        String identifier,
    @Schema(
            description =
                "IBAN, MASKED_CARD or CONTRACT_NUMBER (a ten-digit contract number, kept beside the"
                    + " IBAN); the account's own kind when omitted.")
        @Pattern(regexp = "IBAN|MASKED_CARD|CONTRACT_NUMBER")
        String kind) {

  /** The identifier of the account's own kind. */
  public AccountIdentifierRequest(String identifier) {
    this(identifier, null);
  }

  // The plain account number must never reach a log line, should anything print the request.
  @Override
  public String toString() {
    return "AccountIdentifierRequest[identifier=(hidden), kind=" + kind + "]";
  }
}
