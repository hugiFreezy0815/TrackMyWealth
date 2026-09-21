package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountLoan;
import com.trackmywealth.backend.entity.AccountMortgage;
import com.trackmywealth.backend.entity.CustomAssetValuation;
import com.trackmywealth.backend.repository.AccountLoanRepository;
import com.trackmywealth.backend.repository.AccountMortgageRepository;
import com.trackmywealth.backend.repository.CustomAssetValuationRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DM-17's uniform valuation interface: "what is this account worth, in this currency, as of this
 * date". First extracted from {@code InstitutionService} (US-04-03) once a second consumer - the
 * account balance and net-worth reads of US-09-01 - needed the same resolution and FX handling, so
 * that every aggregation reads an account's value from one place rather than each re-deciding which
 * source applies.
 *
 * <p>Which value source applies is decided by capability flags, never by {@code account_type}
 * ({@code ArchitectureTest}'s {@code only_account_service_branches_on_account_type}, US-05-04):
 * {@code manualValuation} is true for exactly {@code CUSTOM_ASSET} (US-05-05), {@code
 * hasAmortisation} for exactly {@code MORTGAGE}/{@code LOAN} (US-05-01), and {@code
 * hasStatementCycle} for exactly {@code CREDIT_CARD} (US-05-01, US-09-01) - so at most one applies.
 * Every other account type has no value source yet and resolves as unknown, not zero.
 */
@Service
public class AccountValuationService {

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final AccountMortgageRepository accountMortgageRepository;
  private final AccountLoanRepository accountLoanRepository;
  private final CustomAssetValuationRepository customAssetValuationRepository;
  private final TransactionRepository transactionRepository;
  private final FxRateService fxRateService;
  private final String fxDefaultSource;

  public AccountValuationService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      AccountMortgageRepository accountMortgageRepository,
      AccountLoanRepository accountLoanRepository,
      CustomAssetValuationRepository customAssetValuationRepository,
      TransactionRepository transactionRepository,
      FxRateService fxRateService,
      @Value("${app.fx.default-source}") String fxDefaultSource) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.accountMortgageRepository = accountMortgageRepository;
    this.accountLoanRepository = accountLoanRepository;
    this.customAssetValuationRepository = customAssetValuationRepository;
    this.transactionRepository = transactionRepository;
    this.fxRateService = fxRateService;
    this.fxDefaultSource = fxDefaultSource;
  }

  /**
   * US-09-01/FR-CC-001/003: the account's current balance in its own native currency - for a credit
   * card, the outstanding amount owed, a {@code LIABILITY}. Gated at {@code BALANCE_ONLY}
   * (US-03-03), the weakest level that may see a figure at all.
   */
  @Transactional(readOnly = true)
  public AccountValuation getBalance(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.BALANCE_ONLY);
    return valueIn(account, account.getNativeCurrency(), LocalDate.now());
  }

  /**
   * {@code account}'s value in {@code targetCurrency} as of {@code asOf}. Unknown ({@code
   * valueKnown = false}) when the account has no resolvable value, or when it has one in a currency
   * with no FX rate to {@code targetCurrency} at all - never an exception for either, so one
   * unresolvable account degrades to a visible gap instead of failing a whole aggregation.
   */
  @Transactional(readOnly = true)
  public AccountValuation valueIn(Account account, String targetCurrency, LocalDate asOf) {
    Optional<BigDecimal> nativeValue = resolveNativeValue(account, asOf);
    if (nativeValue.isEmpty()) {
      return unknown(account, targetCurrency);
    }

    if (account.getNativeCurrency().equals(targetCurrency)) {
      return known(account, targetCurrency, nativeValue.get(), null, null, false);
    }

    // FR-CUR-011/US-06-03: a current holding's value converts at the valuation date (today), not
    // at any date tied to when the account or its value was originally recorded - see
    // docs/architecture/calculation-methodology.md.
    //
    // PR-012/#78: uses tryGetConversionRate, not a try/catch around getConversionRate.
    // FxRateService's methods are themselves @Transactional and join this method's own physical
    // transaction (REQUIRED propagation), so getConversionRate() throwing marks that shared
    // transaction rollback-only the moment it happens and no catch here can undo that - the
    // caller's commit would then fail with UnexpectedRollbackException, turning "one account has
    // no FX rate" into a 500 for the whole request.
    Optional<CurrencyConversionResult> conversion =
        fxRateService.tryGetConversionRate(
            account.getNativeCurrency(), targetCurrency, asOf, fxDefaultSource);
    if (conversion.isEmpty()) {
      return unknown(account, targetCurrency);
    }

    BigDecimal convertedValue = fxRateService.applyRate(nativeValue.get(), conversion.get());
    return known(
        account,
        targetCurrency,
        convertedValue,
        conversion.get().rate(),
        asOf,
        conversion.get().carriedForward());
  }

  // Only CUSTOM_ASSET (via CustomAssetValuation), MORTGAGE/LOAN (via original_principal - a real
  // stored number, but the loan's original amount, not its current outstanding balance; no
  // amortization tracking exists yet, EPIC 10) and CREDIT_CARD (via its ledger) have a value
  // source today.
  private Optional<BigDecimal> resolveNativeValue(Account account, LocalDate asOf) {
    if (account.isManualValuation()) {
      return customAssetValuationRepository
          .findFirstByAccountIdAndValuationDateLessThanEqualOrderByValuationDateDesc(
              account.getId(), asOf)
          .map(CustomAssetValuation::getValue);
    }
    if (account.isHasAmortisation()) {
      return accountMortgageRepository
          .findById(account.getId())
          .map(AccountMortgage::getOriginalPrincipal)
          .or(
              () ->
                  accountLoanRepository
                      .findById(account.getId())
                      .map(AccountLoan::getOriginalPrincipal));
    }
    if (account.isHasStatementCycle()) {
      // FR-CC-001/003, DM-11: the card's ledger is cash-direction signed (a purchase is negative),
      // so what is owed is the negated sum. A card with no ledger rows owes exactly 0 (a product
      // decision for US-09-01): always a known value, never unknown. Caveat: with no opening-
      // balance mechanism yet (EPIC 25 snapshots), a card that already carried debt when tracking
      // began reads 0 until that debt is recorded.
      // Voided rows count, see TransactionRepository#sumAmountByAccountIdAsOf.
      return Optional.of(
          transactionRepository
              .sumAmountByAccountIdAsOf(account.getId(), asOf)
              .orElse(BigDecimal.ZERO)
              .negate());
    }
    return Optional.empty();
  }

  private static AccountValuation unknown(Account account, String targetCurrency) {
    return new AccountValuation(
        account.getId(),
        account.getName(),
        account.getNature(),
        account.getNativeCurrency(),
        targetCurrency,
        null,
        null,
        null,
        false,
        false);
  }

  private static AccountValuation known(
      Account account,
      String targetCurrency,
      BigDecimal value,
      BigDecimal conversionRate,
      LocalDate conversionRateDate,
      boolean conversionRateCarriedForward) {
    return new AccountValuation(
        account.getId(),
        account.getName(),
        account.getNature(),
        account.getNativeCurrency(),
        targetCurrency,
        value,
        conversionRate,
        conversionRateDate,
        conversionRateCarriedForward,
        true);
  }
}
