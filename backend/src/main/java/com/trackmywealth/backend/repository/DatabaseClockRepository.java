package com.trackmywealth.backend.repository;

import java.time.OffsetDateTime;
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
}
