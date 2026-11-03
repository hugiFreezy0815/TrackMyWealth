package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything {@code AccountValuationService} reads to value a batch of accounts (#380), each kind
 * loaded once for the whole batch, so the number of queries does not grow with the number of
 * accounts. Keyed by account id unless said otherwise; an account without an entry has no such
 * source. Internal to valuation: never part of an API response.
 *
 * @param ownCurrencies each account's own currency ({@code AccountCurrencyService#ownCurrencies})
 * @param openingBalances the opening balance of each account with a ledger that can take one
 *     (US-25-04)
 * @param latestSnapshots for loans and accounts without a ledger (except one holding positions):
 *     the newest snapshot with a balance on or before the valuation date
 * @param latestValuations for custom assets: the latest manual valuation on or before that date,
 *     already resolved to a value
 * @param unresolvedLoanRepayments the loans with a payment whose split is still unknown (US-10-02)
 * @param originalPrincipals the original principal of each mortgage or loan without a statement
 *     balance
 * @param ledgerSums the ledger sum up to the valuation date of each loan without a statement
 *     balance and each card without an opening balance
 * @param ledgerSumsAfterSnapshot keyed by <b>snapshot</b> id: the account's ledger after that
 *     snapshot (an opening balance or a loan statement) up to the valuation date
 * @param latestLedgerDates for each loan with a statement balance: the booking date of its latest
 *     live ledger row on or before the valuation date (#445); no entry without one
 */
public record AccountValueSources(
    Map<UUID, String> ownCurrencies,
    Map<UUID, SnapshotBalance> openingBalances,
    Map<UUID, SnapshotBalance> latestSnapshots,
    Map<UUID, NativeAccountValue> latestValuations,
    Set<UUID> unresolvedLoanRepayments,
    Map<UUID, BigDecimal> originalPrincipals,
    Map<UUID, BigDecimal> ledgerSums,
    Map<UUID, BigDecimal> ledgerSumsAfterSnapshot,
    Map<UUID, LocalDate> latestLedgerDates) {

  public AccountValueSources {
    // Defensive/immutable copies (SpotBugs EI_EXPOSE_REP).
    ownCurrencies = Map.copyOf(ownCurrencies);
    openingBalances = Map.copyOf(openingBalances);
    latestSnapshots = Map.copyOf(latestSnapshots);
    latestValuations = Map.copyOf(latestValuations);
    unresolvedLoanRepayments = Set.copyOf(unresolvedLoanRepayments);
    originalPrincipals = Map.copyOf(originalPrincipals);
    ledgerSums = Map.copyOf(ledgerSums);
    ledgerSumsAfterSnapshot = Map.copyOf(ledgerSumsAfterSnapshot);
    latestLedgerDates = Map.copyOf(latestLedgerDates);
  }
}
