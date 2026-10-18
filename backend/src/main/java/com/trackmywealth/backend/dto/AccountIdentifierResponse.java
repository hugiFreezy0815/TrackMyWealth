package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * US-07-10: an account's registered identifier as far as it is shown: {@code kind} ({@code IBAN},
 * or {@code MASKED_CARD} for a credit card account), the kind of the account's own identifier
 * whether or not one is registered, and {@code identifierMasked} (an IBAN's last 4 characters, or
 * the masked card number), {@code null} while none is registered. {@code contractIdentifierMasked}
 * is the last 4 digits of a contract number registered besides it (#323), {@code null} while none
 * is. {@code version} is the account's {@code If-Match} revision.
 */
public record AccountIdentifierResponse(
    UUID accountId,
    String kind,
    String identifierMasked,
    int version,
    String contractIdentifierMasked) {}
