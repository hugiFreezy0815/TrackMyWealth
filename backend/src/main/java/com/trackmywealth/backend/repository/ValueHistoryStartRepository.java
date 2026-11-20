package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.TrackingStartSources;
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

  /**
   * #607: per account, every dated value source that can start its tracked history, read once for
   * the whole response. Withdrawn snapshots (US-07-12) say nothing; the opening balance is read as
   * {@code AccountValuationService} reads it.
   */
  private static final String TRACKING_START_SOURCES =
      """
      SELECT a.id AS account_id,
        (SELECT MIN(s.snapshot_date) FROM account_snapshot s
          WHERE s.account_id = a.id AND s.withdrawn_at IS NULL AND s.balance IS NOT NULL)
          AS first_balance,
        (SELECT MIN(s.snapshot_date) FROM account_snapshot s
          WHERE s.account_id = a.id AND s.is_opening_balance) AS opening_balance,
        (SELECT MIN(s.snapshot_date) FROM account_snapshot s
          WHERE s.account_id = a.id AND s.withdrawn_at IS NULL
            AND EXISTS (SELECT 1 FROM snapshot_holding h WHERE h.snapshot_id = s.id))
          AS first_holdings,
        (SELECT MIN(v.valuation_date) FROM custom_asset_valuation v WHERE v.account_id = a.id)
          AS first_valuation,
        (EXISTS (SELECT 1 FROM account_mortgage m WHERE m.account_id = a.id)
          OR EXISTS (SELECT 1 FROM account_loan l WHERE l.account_id = a.id))
          AS has_original_principal
      FROM account a
      WHERE a.id IN (:accountIds)
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

  public Map<UUID, TrackingStartSources> trackingStartSources(Collection<UUID> accountIds) {
    if (accountIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, TrackingStartSources> result = new HashMap<>();
    jdbcTemplate.query(
        TRACKING_START_SOURCES,
        new MapSqlParameterSource("accountIds", accountIds),
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              UUID accountId = rs.getObject("account_id", UUID.class);
              result.put(
                  accountId,
                  new TrackingStartSources(
                      accountId,
                      rs.getObject("first_balance", LocalDate.class),
                      rs.getObject("opening_balance", LocalDate.class),
                      rs.getObject("first_holdings", LocalDate.class),
                      rs.getObject("first_valuation", LocalDate.class),
                      rs.getBoolean("has_original_principal")));
            });
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
