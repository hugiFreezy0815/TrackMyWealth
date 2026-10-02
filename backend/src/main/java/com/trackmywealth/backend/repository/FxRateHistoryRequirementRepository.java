package com.trackmywealth.backend.repository;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code fx_rate_history_requirement} (V52, #223): the earliest transaction booking date
 * across all workspaces, kept by a trigger on {@code transaction}. It is how far back the FX import
 * loads history. The table is never written from Java.
 */
@Repository
public class FxRateHistoryRequirementRepository {

  private final JdbcTemplate jdbcTemplate;

  public FxRateHistoryRequirementRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Empty while the ledger holds no transaction at all. */
  public Optional<LocalDate> findEarliestBookingDate() {
    return jdbcTemplate
        .queryForList(
            "SELECT earliest_booking_date FROM fx_rate_history_requirement", LocalDate.class)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }
}
