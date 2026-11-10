package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.AccountHistoryState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Batched, workspace-RLS-protected account membership and archive/restore intervals. */
@Repository
public class AccountHistoryStateRepository {

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public AccountHistoryStateRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Map<UUID, List<AccountHistoryState>> historyFor(Collection<UUID> accountIds) {
    if (accountIds.isEmpty()) {
      return Map.of();
    }
    List<AccountHistoryState> events =
        jdbcTemplate.query(
            """
            SELECT account_id, portfolio_id, status, effective_at, verified
            FROM account_history_state
            WHERE account_id IN (:accountIds)
            ORDER BY account_id, effective_at, id
            """,
            new MapSqlParameterSource("accountIds", accountIds),
            (rs, rowNumber) ->
                new AccountHistoryState(
                    rs.getObject("account_id", UUID.class),
                    rs.getObject("portfolio_id", UUID.class),
                    rs.getString("status"),
                    rs.getObject("effective_at", java.time.OffsetDateTime.class),
                    rs.getBoolean("verified")));
    Map<UUID, List<AccountHistoryState>> byAccount = new HashMap<>();
    for (AccountHistoryState event : events) {
      byAccount.computeIfAbsent(event.accountId(), unused -> new ArrayList<>()).add(event);
    }
    byAccount.replaceAll((id, values) -> List.copyOf(values));
    return Map.copyOf(byAccount);
  }
}
