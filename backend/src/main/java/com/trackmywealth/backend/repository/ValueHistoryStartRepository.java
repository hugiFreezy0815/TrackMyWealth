package com.trackmywealth.backend.repository;

import java.sql.Types;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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

  /**
   * Per-account dated evidence, read once for the whole chart instead of at every history point. A
   * missing opening date must not imply the account existed indefinitely. Valid backdated bookings,
   * snapshots and manual valuations still predate the account record when appropriate.
   */
  private static final String FIRST_EVIDENCE =
      """
      SELECT account_id, MIN(activity_date) FROM (
        SELECT t.account_id, t.booking_date AS activity_date
          FROM transaction t
          WHERE t.account_id IN (:accountIds) AND t.deleted_at IS NULL
            AND t.voided_at IS NULL AND t.replaces_transaction_id IS NULL
        UNION ALL
        SELECT s.account_id, s.snapshot_date
          FROM account_snapshot s
          WHERE s.account_id IN (:accountIds) AND s.withdrawn_at IS NULL
        UNION ALL
        SELECT v.account_id, v.valuation_date
          FROM custom_asset_valuation v
          WHERE v.account_id IN (:accountIds)
      ) events GROUP BY account_id
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public ValueHistoryStartRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Map<UUID, LocalDate> firstEvidence(Collection<UUID> accountIds) {
    if (accountIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, LocalDate> result = new HashMap<>();
    jdbcTemplate.query(
        FIRST_EVIDENCE,
        new MapSqlParameterSource("accountIds", accountIds),
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs ->
                result.put(
                    rs.getObject("account_id", UUID.class), rs.getObject("min", LocalDate.class)));
    return Map.copyOf(result);
  }

  public LocalDate firstActivity(UUID workspaceId, @Nullable UUID portfolioId, LocalDate today) {
    MapSqlParameterSource params =
        new MapSqlParameterSource("workspaceId", workspaceId)
            .addValue("portfolioId", portfolioId, Types.OTHER);
    LocalDate first = jdbcTemplate.queryForObject(FIRST_ACTIVITY, params, LocalDate.class);
    return first == null || first.isAfter(today) ? today : first;
  }
}
