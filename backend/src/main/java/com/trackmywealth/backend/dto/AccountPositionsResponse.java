package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * US-15-05: one account's latest non-withdrawn holdings snapshot (including its date), or an empty
 * position list when no such snapshot exists. Not paged: one snapshot holds at most {@value
 * #MAX_POSITIONS} holdings, the most a holdings import accepts (a manual snapshot accepts fewer).
 */
public record AccountPositionsResponse(
    UUID accountId,
    String currency,
    @Nullable LocalDate asOf,
    AccountPositionsSource source,
    boolean complete,
    @Schema(
            description =
                "All holdings of one snapshot, by valueInAccountCurrency descending (unknown"
                    + " values last), then securityId. Not paged: a snapshot holds at most "
                    + MAX_POSITIONS
                    + " holdings, the most one holdings import accepts.")
        @Size(max = AccountPositionsResponse.MAX_POSITIONS)
        List<AccountPositionResponse> positions) {

  /** Must not be lower than any limit on the holdings of one snapshot (import or manual). */
  public static final int MAX_POSITIONS = 2_000;

  public AccountPositionsResponse {
    positions = List.copyOf(positions);
  }
}
