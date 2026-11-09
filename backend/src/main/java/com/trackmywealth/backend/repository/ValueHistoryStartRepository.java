package com.trackmywealth.backend.repository;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Earliest recorded financial activity for MAX, not merely when the workspace was created. */
@Repository
public class ValueHistoryStartRepository {

  private static final String FIRST_ACTIVITY =
      """
      SELECT MIN(activity_date) FROM (
        SELECT COALESCE(opened_at, created_at::date) AS activity_date
          FROM account WHERE workspace_id = ?
        UNION ALL
        SELECT t.booking_date FROM transaction t
          JOIN account a ON a.id = t.account_id WHERE a.workspace_id = ?
        UNION ALL
        SELECT s.snapshot_date FROM account_snapshot s
          JOIN account a ON a.id = s.account_id WHERE a.workspace_id = ?
            AND s.withdrawn_at IS NULL
        UNION ALL
        SELECT v.valuation_date FROM custom_asset_valuation v
          JOIN account a ON a.id = v.account_id WHERE a.workspace_id = ?
      ) activity
      """;

  private final JdbcTemplate jdbcTemplate;

  public ValueHistoryStartRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public LocalDate firstActivity(UUID workspaceId, LocalDate today) {
    LocalDate first =
        jdbcTemplate.queryForObject(
            FIRST_ACTIVITY, LocalDate.class, workspaceId, workspaceId, workspaceId, workspaceId);
    return first == null || first.isAfter(today) ? today : first;
  }
}
