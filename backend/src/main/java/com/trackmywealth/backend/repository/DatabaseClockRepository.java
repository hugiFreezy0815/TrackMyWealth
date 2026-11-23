package com.trackmywealth.backend.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The database's own "now", for a timestamp that is compared with ones the database writes itself
 * ({@code DEFAULT now()} columns such as {@code transaction.created_at}). Taken from the
 * application's {@code Clock}, such a timestamp would be ordered against another machine's clock,
 * and any skew between the two would reorder them.
 */
@Repository
public class DatabaseClockRepository {

  // latest: the row's newest event (GREATEST skips NULLs: a row with no categorization log row is
  // bounded by its created_at alone). One microsecond after it, not at it: within one instant the
  // history puts a row's end before its other events.
  private static final String REMOVAL_INSTANTS =
      """
      SELECT id,
             CASE WHEN latest > now() THEN latest + interval '1 microsecond' ELSE now() END
                 AS removed_at
        FROM (SELECT t.id,
                     GREATEST(t.created_at,
                              (SELECT max(l.assigned_at) FROM transaction_categorization_log l
                                WHERE l.transaction_id = t.id)) AS latest
                FROM transaction t
               WHERE t.id = ANY (?)) rows
      """;

  private final JdbcTemplate jdbcTemplate;

  public DatabaseClockRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * PostgreSQL's {@code now()}: the start of the current database transaction, the same instant a
   * {@code DEFAULT now()} column written in it receives.
   */
  public OffsetDateTime now() {
    return jdbcTemplate.queryForObject("SELECT now()", OffsetDateTime.class);
  }

  /**
   * The instant to stamp each of {@code transactionIds} as voided or deleted (US-07-38): {@link
   * #now()}, unless the row already holds a later event - its creation or a category decision that
   * another database transaction, started after this one, committed while this one waited for the
   * row's lock - and then one microsecond after that event. {@code now()} is this transaction's
   * start, not the moment of the removal, so such an event would otherwise sort after the removal
   * that ended the row. Call it after the rows are locked. Without such a race every row gets
   * exactly {@code now()}, the instant a replacement created in this transaction gets as its {@code
   * created_at}.
   */
  public Map<UUID, OffsetDateTime> removalInstants(Collection<UUID> transactionIds) {
    Map<UUID, OffsetDateTime> instants = new HashMap<>();
    if (transactionIds.isEmpty()) {
      return instants;
    }
    jdbcTemplate.query(
        REMOVAL_INSTANTS,
        statement ->
            statement.setArray(
                1, statement.getConnection().createArrayOf("uuid", transactionIds.toArray())),
        rs -> {
          instants.put(
              rs.getObject("id", UUID.class), rs.getObject("removed_at", OffsetDateTime.class));
        });
    return instants;
  }
}
