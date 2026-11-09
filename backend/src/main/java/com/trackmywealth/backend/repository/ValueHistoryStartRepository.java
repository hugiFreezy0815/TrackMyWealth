package com.trackmywealth.backend.repository;

import java.sql.Types;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Earliest recorded financial activity for MAX, not merely when a portfolio or workspace was
 * created. The same query is scope-limited by the portfolio id, when supplied, under database RLS.
 */
@Repository
public class ValueHistoryStartRepository {

  private static final String FIRST_ACTIVITY =
      """
      SELECT MIN(activity_date) FROM (
        SELECT COALESCE(a.opened_at, a.created_at::date) AS activity_date
          FROM account a
          WHERE a.workspace_id = :workspaceId
            AND (CAST(:portfolioId AS uuid) IS NULL OR a.portfolio_id = CAST(:portfolioId AS uuid))
        UNION ALL
        SELECT t.booking_date FROM transaction t
          JOIN account a ON a.id = t.account_id
          WHERE a.workspace_id = :workspaceId
            AND (CAST(:portfolioId AS uuid) IS NULL OR a.portfolio_id = CAST(:portfolioId AS uuid))
        UNION ALL
        SELECT s.snapshot_date FROM account_snapshot s
          JOIN account a ON a.id = s.account_id
          WHERE a.workspace_id = :workspaceId
            AND (CAST(:portfolioId AS uuid) IS NULL OR a.portfolio_id = CAST(:portfolioId AS uuid))
            AND s.withdrawn_at IS NULL
        UNION ALL
        SELECT v.valuation_date FROM custom_asset_valuation v
          JOIN account a ON a.id = v.account_id
          WHERE a.workspace_id = :workspaceId
            AND (CAST(:portfolioId AS uuid) IS NULL OR a.portfolio_id = CAST(:portfolioId AS uuid))
      ) activity
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public ValueHistoryStartRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public LocalDate firstActivity(UUID workspaceId, @Nullable UUID portfolioId, LocalDate today) {
    MapSqlParameterSource params =
        new MapSqlParameterSource("workspaceId", workspaceId)
            .addValue("portfolioId", portfolioId, Types.OTHER);
    LocalDate first = jdbcTemplate.queryForObject(FIRST_ACTIVITY, params, LocalDate.class);
    return first == null || first.isAfter(today) ? today : first;
  }
}
