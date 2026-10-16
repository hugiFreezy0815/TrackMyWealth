package com.trackmywealth.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * US-07-10: an account's registered identifier as far as it is shown: {@code kind} ({@code IBAN},
 * or {@code MASKED_CARD} for a credit card account) and {@code identifierMasked} (an IBAN's last 4
 * characters, or the masked card number), {@code null} while none is registered. {@code version} is
 * the account's {@code If-Match} revision.
 */
public record AccountIdentifierResponse(
    UUID accountId,
    String kind,
    String identifierMasked,
    int version,
    @JsonInclude(JsonInclude.Include.NON_NULL) String contractIdentifierMasked) {

  public AccountIdentifierResponse(UUID accountId, String kind, String identifierMasked, int version) {
    this(accountId, kind, identifierMasked, version, null);
  }
}
