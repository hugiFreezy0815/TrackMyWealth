package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * #607: the flows a period return weights by date - external income and spending plus every
 * internal-transfer leg, per account and booking date, each converted at its own day's rate - and
 * the account days with rows still pending review. A flow's {@code amount} is null when its rate is
 * unknown. Two legs of a transfer between tracked accounts of a scope cancel out; a leg whose
 * counterpart is outside the scope, or in it but untracked, is money entering or leaving it.
 */
public record DatedCashFlows(List<Flow> flows, List<Flow> pending) {

  public DatedCashFlows {
    flows = List.copyOf(flows);
    pending = List.copyOf(pending);
  }

  /** Signed cash-direction amount in the requested currency, or null without an FX rate. */
  public record Flow(UUID accountId, LocalDate date, @Nullable BigDecimal amount) {}
}
