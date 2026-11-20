package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * #607: the dated facts every scope of one history response shares - the period, each tracked
 * account's tracking start, the business days its lifecycle or portfolio state changed, and whether
 * it is in the scope on a day, by its effective state then.
 */
public record ReturnScopeContext(
    LocalDate from,
    LocalDate to,
    Map<UUID, LocalDate> trackingStarts,
    Map<UUID, List<LocalDate>> stateChangeDays,
    BiPredicate<UUID, LocalDate> included) {

  public ReturnScopeContext {
    trackingStarts = Map.copyOf(trackingStarts);
    stateChangeDays = Map.copyOf(stateChangeDays);
  }
}
