package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.FxRate;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulk insert for the FX import (US-06-04, #223). JDBC rather than {@code FxRateRepository#save}: a
 * backfill of several years is tens of thousands of rows, which JPA would insert one statement and
 * one round trip at a time, and a rate already stored must be skipped rather than fail the whole
 * batch on {@code fx_rate}'s {@code UNIQUE(base_currency, quote_currency, rate_date, source)}.
 *
 * <p>Also derives the cross rates between the currencies in use (V54) from the published {@code
 * EUR/<currency>} rows, in the database: tens of thousands of rows for a long history, none of
 * which needs to pass through Java.
 */
@Repository
public class FxRateBatchRepository {

  private static final int BATCH_SIZE = 1000;

  private static final String INSERT_IF_ABSENT =
      "INSERT INTO fx_rate (base_currency, quote_currency, rate_date, rate, source)"
          + " VALUES (?, ?, ?, ?, ?)"
          + " ON CONFLICT (base_currency, quote_currency, rate_date, source) DO NOTHING";

  // Every ordered pair of distinct currencies in use (and EUR), except EUR/x, which is published:
  // x/y = (EUR/y) / (EUR/x), the euro's own rate being 1. The dividend is widened so the quotient
  // carries 30 decimal places before the single rounding to fx_rate's ten - the same value
  // FxRateService computes for an unstored pair. A quotient fx_rate cannot hold is left out.
  private static final String DERIVE_CROSS_RATES_TEMPLATE =
      """
      WITH published AS (
          SELECT f.rate_date, f.quote_currency AS currency, f.rate
          FROM fx_rate f
          WHERE f.source = ? AND f.base_currency = 'EUR' AND NOT f.derived
            AND f.rate_date BETWEEN ? AND ?
            AND f.quote_currency IN (SELECT currency FROM fx_rate_currency_in_use)
      ),
      day_rates AS (
          SELECT rate_date, currency, rate FROM published
          UNION ALL
          SELECT DISTINCT rate_date, 'EUR', 1 FROM published
      ),
      crosses AS (
          SELECT a.currency AS base_currency, b.currency AS quote_currency, a.rate_date,
                 round(b.rate::NUMERIC(40, 30) / a.rate, 10) AS rate
          FROM day_rates a
          JOIN day_rates b ON b.rate_date = a.rate_date AND b.currency <> a.currency
          WHERE a.currency <> 'EUR' /* new-currency filter */
      )
      INSERT INTO fx_rate (base_currency, quote_currency, rate_date, rate, source, derived)
      SELECT base_currency, quote_currency, rate_date, rate, ?, TRUE
      FROM crosses
      WHERE rate > 0 AND rate < 1e10
      ON CONFLICT (base_currency, quote_currency, rate_date, source) DO NOTHING
      """;

  private static final String NEW_CURRENCY_FILTER = "/* new-currency filter */";

  private static final String DERIVE_CROSS_RATES = DERIVE_CROSS_RATES_TEMPLATE;

  // For a currency newly in use only its own pairs are missing - those between the others were
  // derived when they came into use - so over the whole history only pairs naming one of them.
  private static final String DERIVE_CROSS_RATES_OF_NEW_CURRENCIES =
      DERIVE_CROSS_RATES_TEMPLATE.replace(
          NEW_CURRENCY_FILTER,
          """
            AND (a.currency IN (SELECT currency FROM fx_rate_currency_in_use
                                WHERE NOT cross_rates_derived)
              OR b.currency IN (SELECT currency FROM fx_rate_currency_in_use
                                WHERE NOT cross_rates_derived))""");

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

  /**
   * Stores the cross rates between the currencies in use for every day from {@code from} to {@code
   * to} that has published rates of {@code source}, skipping any already stored.
   *
   * @return how many rows were inserted
   */
  public int deriveCrossRates(String source, LocalDate from, LocalDate to) {
    return jdbcTemplate.update(
        DERIVE_CROSS_RATES, source, Date.valueOf(from), Date.valueOf(to), source);
  }

  /**
   * {@link #deriveCrossRates} for {@code from} to {@code to}, limited to the pairs that name a
   * currency in use whose cross rates have not been derived yet.
   *
   * @return how many rows were inserted
   */
  public int deriveCrossRatesOfNewCurrencies(String source, LocalDate from, LocalDate to) {
    return jdbcTemplate.update(
        DERIVE_CROSS_RATES_OF_NEW_CURRENCIES, source, Date.valueOf(from), Date.valueOf(to), source);
  }

  /** The ids of the currencies in use whose cross rates have not been derived yet. */
  public List<UUID> findCurrenciesNotYetDerived() {
    return jdbcTemplate.queryForList(
        "SELECT id FROM fx_rate_currency_in_use WHERE NOT cross_rates_derived", UUID.class);
  }

  /** Marks these currencies' cross rates as derived over the whole stored history. */
  public void markCrossRatesDerived(List<UUID> currencyIds) {
    jdbcTemplate.batchUpdate(
        "UPDATE fx_rate_currency_in_use SET cross_rates_derived = TRUE WHERE id = ?",
        currencyIds,
        BATCH_SIZE,
        (statement, id) -> statement.setObject(1, id));
  }
}
