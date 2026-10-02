package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.FxRate;
import java.sql.Date;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulk insert for the FX import (US-06-04, #223). JDBC rather than {@code FxRateRepository#save}: a
 * backfill of several years is tens of thousands of rows, which JPA would insert one statement and
 * one round trip at a time, and a rate already stored must be skipped rather than fail the whole
 * batch on {@code fx_rate}'s {@code UNIQUE(base_currency, quote_currency, rate_date, source)}.
 */
@Repository
public class FxRateBatchRepository {

  private static final int BATCH_SIZE = 1000;

  private static final String INSERT_IF_ABSENT =
      "INSERT INTO fx_rate (base_currency, quote_currency, rate_date, rate, source)"
          + " VALUES (?, ?, ?, ?, ?)"
          + " ON CONFLICT (base_currency, quote_currency, rate_date, source) DO NOTHING";

  private final JdbcTemplate jdbcTemplate;

  public FxRateBatchRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Inserts every rate not yet stored for its pair, date and source, leaving stored ones untouched
   * - the first stored rate for a day is the one conversions have already used.
   *
   * @return how many rows were inserted
   */
  public int insertIfAbsent(List<FxRate> rates) {
    int[][] counts =
        jdbcTemplate.batchUpdate(
            INSERT_IF_ABSENT,
            rates,
            BATCH_SIZE,
            (statement, rate) -> {
              statement.setString(1, rate.getBaseCurrency());
              statement.setString(2, rate.getQuoteCurrency());
              statement.setDate(3, Date.valueOf(rate.getRateDate()));
              statement.setBigDecimal(4, rate.getRate());
              statement.setString(5, rate.getSource());
            });
    int inserted = 0;
    for (int[] batch : counts) {
      for (int count : batch) {
        inserted += Math.max(count, 0);
      }
    }
    return inserted;
  }
}
