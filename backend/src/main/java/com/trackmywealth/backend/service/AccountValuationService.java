package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.NativeAccountValue;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountLoan;
import com.trackmywealth.backend.entity.AccountMortgage;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.repository.AccountLoanRepository;
import com.trackmywealth.backend.repository.AccountMortgageRepository;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.CustomAssetValuationRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.validation.CurrencyCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
 *
 * <p>US-25-04: an account with an opening balance - any account except the first two kinds, which
 * reject one ({@code OpeningBalanceService}) - is valued as that balance plus its ledger after the
 * opening date, and is unknown before that date. A card's own ledger value is superseded by it. An
 * account that {@code holdsPositions} keeps an unknown value even with one: its cash is only part
 * of it until holdings are valued (EPIC 15). An account without a ledger ({@code hasTransactions =
 * false}) is valued from its latest snapshot instead, the opening balance being one of them. Every
 * other account without an opening balance has no value source yet and resolves as unknown, not
 * zero.
 */
@Service
public class AccountValuationService {

  private static final String LIABILITY = "LIABILITY";

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final AccountMortgageRepository accountMortgageRepository;
  private final AccountLoanRepository accountLoanRepository;
  private final CustomAssetValuationRepository customAssetValuationRepository;
  private final AccountSnapshotRepository accountSnapshotRepository;
  private final TransactionRepository transactionRepository;
  private final AccountCurrencyService accountCurrencyService;
  private final AccountDataQualityService accountDataQualityService;
  private final FxRateService fxRateService;
  private final BusinessDateService businessDateService;
  private final String fxDefaultSource;

  public AccountValuationService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      AccountMortgageRepository accountMortgageRepository,
      AccountLoanRepository accountLoanRepository,
      CustomAssetValuationRepository customAssetValuationRepository,
      AccountSnapshotRepository accountSnapshotRepository,
      TransactionRepository transactionRepository,
      AccountCurrencyService accountCurrencyService,
      AccountDataQualityService accountDataQualityService,
      FxRateService fxRateService,
      BusinessDateService businessDateService,
      @Value("${app.fx.default-source}") String fxDefaultSource) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.accountMortgageRepository = accountMortgageRepository;
    this.accountLoanRepository = accountLoanRepository;
    this.customAssetValuationRepository = customAssetValuationRepository;
    this.accountSnapshotRepository = accountSnapshotRepository;
    this.transactionRepository = transactionRepository;
    this.accountCurrencyService = accountCurrencyService;
    this.accountDataQualityService = accountDataQualityService;
    this.fxRateService = fxRateService;
    this.businessDateService = businessDateService;
    this.fxDefaultSource = fxDefaultSource;
  }

  /**
   * US-09-01/FR-CC-001/003: the account's balance as of {@code asOf} (today when {@code null}) -
   * for a credit card, the outstanding amount owed, a {@code LIABILITY}. The current balance is
   * gated at {@code BALANCE_ONLY} (US-03-03), the weakest level that may see a figure at all. A
   * past date needs {@code READ}: two consecutive days' balances differ by that day's transactions,
   * which a {@code BALANCE_ONLY} grant must not reveal (#241 review). A date in the future is a
   * 422: a balance is recorded history, not a forecast. Past, today and future are judged against
   * {@link BusinessDateService#today}, never the client's date.
   *
   * <p>US-06-05: in the account's own currency, or in {@code requestedCurrency} when given,
   * converted at the valuation date's rate. Both {@code asOf} and the currency are validated only
   * after the access check, so an invalid value cannot tell a caller whether an account it may not
   * see exists.
   */
  @Transactional(readOnly = true)
  public AccountValuation getBalance(
      UUID accountId, LocalDate asOf, AuthenticatedUserPrincipal actor, String requestedCurrency) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    LocalDate today = businessDateService.today();
    boolean historical = asOf != null && asOf.isBefore(today);
    accessControlService.requireAccountAccess(
        actor, account, historical ? AccessLevelValues.READ : AccessLevelValues.BALANCE_ONLY);
    if (asOf != null && asOf.isAfter(today)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "asOf cannot be in the future.");
    }
    String targetCurrency =
        CurrencyCodes.requestedOrDefault(
            requestedCurrency, accountCurrencyService.ownCurrency(account));
    return valueIn(account, targetCurrency, asOf == null ? today : asOf);
  }

  /**
   * {@code account}'s value in {@code targetCurrency} as of {@code asOf}. Unknown ({@code
   * valueKnown = false}) when the account has no resolvable value, or when it has one in a currency
   * with no FX rate to {@code targetCurrency} at all - never an exception for either, so one
   * unresolvable account degrades to a visible gap instead of failing a whole aggregation.
   *
   * <p><b>Performs no access check.</b> The caller must already have established that the acting
   * member may see {@code account} at {@code BALANCE_ONLY} or above (as {@link #getBalance}, {@code
   * InstitutionService#getSummary} and {@code NetWorthService#getNetWorth} each do) - otherwise a
   * figure leaks to a member who has no grant on the account.
   */
  @Transactional(readOnly = true)
  public AccountValuation valueIn(Account account, String targetCurrency, LocalDate asOf) {
    // Read once: both the value and the warnings depend on it.
    Optional<AccountSnapshot> openingBalance =
        takesOpeningBalance(account)
            ? accountSnapshotRepository.findByAccountIdAndOpeningBalanceTrue(account.getId())
            : Optional.empty();
    List<String> warnings =
        accountDataQualityService.warningsFor(
            account, openingBalance.map(AccountSnapshot::getSnapshotDate).orElse(null));
    return valueIn(
        account, targetCurrency, asOf, rateLookup(targetCurrency, asOf), openingBalance, warnings);
  }

  /**
   * {@link #valueIn} for several accounts in one target currency, resolving each distinct native
   * currency's FX rate once rather than once per account, and the accounts' opening balances and
   * their warnings in one query each for the whole batch (#241 review), not two per account. Same
   * no-access-check contract as {@link #valueIn}: pass only accounts the caller has already been
   * cleared to see.
   */
  @Transactional(readOnly = true)
  public List<AccountValuation> valueAll(
      Collection<Account> accounts, String targetCurrency, LocalDate asOf) {
    Function<String, Optional<CurrencyConversionResult>> lookup = rateLookup(targetCurrency, asOf);
    Map<String, Optional<CurrencyConversionResult>> resolved = new HashMap<>();
    List<UUID> eligible =
        accounts.stream()
            .filter(AccountValuationService::takesOpeningBalance)
            .map(Account::getId)
            .toList();
    Map<UUID, AccountSnapshot> openingBalances =
        eligible.isEmpty()
            ? Map.of()
            : accountSnapshotRepository.findByAccountIdInAndOpeningBalanceTrue(eligible).stream()
                .collect(
                    Collectors.toMap(
                        snapshot -> snapshot.getAccount().getId(), snapshot -> snapshot));
    Map<UUID, List<String>> warnings =
        accountDataQualityService.warningsForAll(
            accounts,
            openingBalances.entrySet().stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().getSnapshotDate())));
    return accounts.stream()
        .map(
            account ->
                valueIn(
                    account,
                    targetCurrency,
                    asOf,
                    nativeCurrency -> resolved.computeIfAbsent(nativeCurrency, lookup),
                    Optional.ofNullable(openingBalances.get(account.getId())),
                    warnings.getOrDefault(account.getId(), List.of())))
        .toList();
  }

  // An account with a value source of its own takes no opening balance (OpeningBalanceService),
  // so it is not looked up at all.
  private static boolean takesOpeningBalance(Account account) {
    return !account.isManualValuation() && !account.isHasAmortisation();
  }

  private Function<String, Optional<CurrencyConversionResult>> rateLookup(
      String targetCurrency, LocalDate asOf) {
    // PR-012/#78: uses tryGetConversionRate, not a try/catch around getConversionRate.
    // FxRateService's methods are themselves @Transactional and join this method's own physical
    // transaction (REQUIRED propagation), so getConversionRate() throwing marks that shared
    // transaction rollback-only the moment it happens and no catch here can undo that - the
    // caller's commit would then fail with UnexpectedRollbackException, turning "one account has
    // no FX rate" into a 500 for the whole request.
    return nativeCurrency ->
        fxRateService.tryGetConversionRate(nativeCurrency, targetCurrency, asOf, fxDefaultSource);
  }

  private AccountValuation valueIn(
      Account account,
      String targetCurrency,
      LocalDate asOf,
      Function<String, Optional<CurrencyConversionResult>> rateForNativeCurrency,
      Optional<AccountSnapshot> openingBalance,
      List<String> warnings) {
    // US-09-04: for a CREDIT_CARD this is billing_currency, not account.nativeCurrency - see
    // AccountCurrencyService. Every other account type's own currency is simply its
    // nativeCurrency.
    String ownCurrency = accountCurrencyService.ownCurrency(account);
    Optional<NativeAccountValue> nativeValue =
        resolveNativeAccountValue(account, openingBalance, asOf);
    if (nativeValue.isEmpty()) {
      return unknown(account, ownCurrency, targetCurrency, warnings);
    }
    NativeAccountValue resolved = nativeValue.get();

    if (ownCurrency.equals(targetCurrency)) {
      // Rounded, not returned raw: resolved.amount() can carry more than money's usual 4 decimal
      // places once it is a card balance summed via fx_rate_to_account_currency (NUMERIC(20,10)) -
      // the same NFR-CALC-007 policy the cross-currency path below already applies via applyRate.
      BigDecimal value =
          resolved.amount().setScale(FxRateService.MONEY_SCALE, RoundingMode.HALF_UP);
      return known(account, ownCurrency, targetCurrency, resolved, value, null, warnings);
    }

    // FR-CUR-011/US-06-03: a current holding's value converts at the valuation date (today), not
    // at any date tied to when the account or its value was originally recorded - see
    // docs/architecture/calculation-methodology.md.
    Optional<CurrencyConversionResult> conversion = rateForNativeCurrency.apply(ownCurrency);
    if (conversion.isEmpty()) {
      return unknown(account, ownCurrency, targetCurrency, warnings);
    }

    BigDecimal convertedValue = fxRateService.applyRate(resolved.amount(), conversion.get());
    return known(
        account, ownCurrency, targetCurrency, resolved, convertedValue, conversion.get(), warnings);
  }

  // CUSTOM_ASSET (via CustomAssetValuation), MORTGAGE/LOAN (via original_principal - a real stored
  // number, but the loan's original amount, not its current outstanding balance; no amortization
  // tracking exists yet, EPIC 10), an account without a ledger (via its latest snapshot), any
  // account with an opening balance (US-25-04) and CREDIT_CARD (via its ledger) have a value source
  // today.
  private Optional<NativeAccountValue> resolveNativeAccountValue(
      Account account, Optional<AccountSnapshot> openingBalance, LocalDate asOf) {
    if (account.isManualValuation()) {
      return customAssetValuationRepository
          .findFirstByAccountIdAndValuationDateLessThanEqualOrderByValuationDateDesc(
              account.getId(), asOf)
          .map(
              v ->
                  new NativeAccountValue(
                      v.getValue(), ValueBasisValues.MANUAL_VALUATION, v.getValuationDate()));
    }
    if (account.isHasAmortisation()) {
      return accountMortgageRepository
          .findById(account.getId())
          .map(AccountMortgage::getOriginalPrincipal)
          .or(
              () ->
                  accountLoanRepository
                      .findById(account.getId())
                      .map(AccountLoan::getOriginalPrincipal))
          .map(principal -> new NativeAccountValue(principal, ValueBasisValues.ORIGINAL_PRINCIPAL));
    }
    if (!account.isHasTransactions()) {
      return latestSnapshot(account, asOf);
    }
    if (openingBalance.isPresent()) {
      return fromOpeningBalance(account, openingBalance.get(), asOf);
    }
    if (account.isHasStatementCycle()) {
      // FR-CC-001/003, DM-11: the card's ledger is cash-direction signed (a purchase is negative),
      // so what is owed is the negated sum. A card with no ledger rows owes exactly 0 (a product
      // decision for US-09-01): always a known value, never unknown - but flagged LEDGER_EMPTY so
      // a client can tell an assumed zero from a measured one. A card that already carried debt
      // when tracking began reads 0 until that debt is recorded as its opening balance (above).
      // A void pair counts as zero, see TransactionRepository#sumAmountByAccountIdAsOf. US-09-04: a
      // foreign-currency card row's `amount` is in its own original currency, not the account's -
      // that query already converts each row via fxRateToAccountCurrency before summing, so this
      // call site needs no change of its own.
      return Optional.of(
          transactionRepository
              .sumAmountByAccountIdAsOf(account.getId(), asOf)
              .map(sum -> new NativeAccountValue(sum.negate(), ValueBasisValues.LEDGER))
              .orElseGet(
                  () -> new NativeAccountValue(BigDecimal.ZERO, ValueBasisValues.LEDGER_EMPTY)));
    }
    return Optional.empty();
  }

  /**
   * #232 review: an account without a ledger ({@code VESTED_BENEFITS}) changes value only through
   * what its provider reports, so its value is its newest snapshot balance on or before {@code
   * asOf} - the opening balance included, being a snapshot too - with that snapshot's date, so a
   * client can show how old it is. Nothing recorded by then: unknown, not zero. An account that
   * {@code holdsPositions} stays unknown, as with an opening balance (EPIC 15).
   */
  private Optional<NativeAccountValue> latestSnapshot(Account account, LocalDate asOf) {
    if (account.isHoldsPositions()) {
      return Optional.empty();
    }
    return accountSnapshotRepository
        .findFirstByAccountIdAndSnapshotDateLessThanEqualAndBalanceIsNotNullOrderBySnapshotDateDescCreatedAtDesc(
            account.getId(), asOf)
        .map(
            snapshot ->
                new NativeAccountValue(
                    snapshot.getBalance(),
                    ValueBasisValues.LATEST_SNAPSHOT,
                    snapshot.getSnapshotDate()));
  }

  /**
   * US-25-04/FR-REC-007, the "ledger from opening balance" source: the balance plus the ledger
   * booked after the opening date, up to and including {@code asOf}. Ordinary rows on the opening
   * date are already contained in the balance; the deliberate exception is a reconciliation {@code
   * VALUATION_ADJUSTMENT} booked after the opening balance was stated, for a provider observation
   * on that same date. Rows before it predate the starting point and are left out (with {@code
   * TRANSACTIONS_BEFORE_OPENING_BALANCE}, see {@link AccountDataQualityService}). Before the
   * opening date nothing is known, so the value is unknown, not zero (PR-011).
   *
   * <p>The balance follows the snapshot convention - a liability's is the positive amount owed -
   * while the ledger is cash-direction signed, so a liability subtracts its ledger sum: a card
   * purchase (negative) increases what is owed. Read from {@code nature}, never from the type.
   */
  private Optional<NativeAccountValue> fromOpeningBalance(
      Account account, AccountSnapshot openingBalance, LocalDate asOf) {
    if (account.isHoldsPositions() || asOf.isBefore(openingBalance.getSnapshotDate())) {
      return Optional.empty();
    }
    BigDecimal ledger =
        transactionRepository
            .sumAmountByAccountIdBookedAfter(
                account.getId(),
                openingBalance.getSnapshotDate(),
                openingBalance.getStatedAt(),
                asOf)
            .orElse(BigDecimal.ZERO);
    BigDecimal value =
        LIABILITY.equals(account.getNature())
            ? openingBalance.getBalance().subtract(ledger)
            : openingBalance.getBalance().add(ledger);
    return Optional.of(new NativeAccountValue(value, ValueBasisValues.LEDGER_FROM_OPENING_BALANCE));
  }

  private static AccountValuation unknown(
      Account account, String ownCurrency, String targetCurrency, List<String> warnings) {
    return new AccountValuation(
        account.getId(),
        account.getName(),
        account.getNature(),
        ownCurrency,
        targetCurrency,
        null,
        null,
        null,
        false,
        false,
        false,
        null,
        null,
        warnings);
  }

  private static AccountValuation known(
      Account account,
      String ownCurrency,
      String targetCurrency,
      NativeAccountValue source,
      BigDecimal value,
      CurrencyConversionResult conversion,
      List<String> warnings) {
    // FR-CUR-011: the conversion date is the date the rate was requested for (the valuation date),
    // not the date of the stored rate it resolved to - carriedForward/stale say how far apart.
    return new AccountValuation(
        account.getId(),
        account.getName(),
        account.getNature(),
        ownCurrency,
        targetCurrency,
        value,
        conversion == null ? null : conversion.rate(),
        conversion == null ? null : conversion.requestedDate(),
        conversion != null && conversion.carriedForward(),
        conversion != null && conversion.stale(),
        true,
        source.basis(),
        source.sourceDate(),
        warnings);
  }
}
