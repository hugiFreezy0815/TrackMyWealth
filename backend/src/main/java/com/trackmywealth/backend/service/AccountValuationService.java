package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AccountValueSources;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.KeyedAmount;
import com.trackmywealth.backend.dto.KeyedDate;
import com.trackmywealth.backend.dto.MultiCurrencyValuations;
import com.trackmywealth.backend.dto.NativeAccountValue;
import com.trackmywealth.backend.dto.SnapshotBalance;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountLoan;
import com.trackmywealth.backend.entity.AccountMortgage;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.CustomAssetValuation;
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
import java.time.Period;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * DM-17's uniform valuation interface: "what is this account worth, in this currency, as of this
 * date". First extracted from {@code PortfolioService} (US-04-03) once a second consumer - the
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
 *
 * <p>#380: every read goes through {@link #valuationsOf}, which loads each kind of value source
 * once for the whole batch ({@link AccountValueSources}), so valuing fifty accounts runs the same
 * number of queries as valuing one. {@link #valueIn} is that same path for a single account - there
 * is no second way to resolve a value. Only the sources some account of the batch can use are read.
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
  private final Period snapshotStaleAfter;

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
      @Value("${app.fx.default-source}") String fxDefaultSource,
      @Value("${app.valuation.snapshot-stale-after:P45D}") Period snapshotStaleAfter) {
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
    // A non-positive period would mark every snapshot stale, one taken today included.
    if (snapshotStaleAfter.isNegative() || snapshotStaleAfter.isZero()) {
      throw new IllegalArgumentException(
          "app.valuation.snapshot-stale-after must be a positive period.");
    }
    this.snapshotStaleAfter = snapshotStaleAfter;
  }

  /**
   * US-09-01/FR-CC-001/003: the account's balance as of {@code asOf} (today when {@code null}) -
   * for a credit card, the outstanding amount owed, a {@code LIABILITY}. Needs access to the
   * account, for today and any past date alike (US-03-06). A date in the future is a 422: a balance
   * is recorded history, not a forecast. Past, today and future are judged against {@link
   * BusinessDateService#today}, never the client's date.
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
    accessControlService.requireAccountAccess(actor, account);
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
   * member has access to {@code account} (as {@link #getBalance}, {@code
   * PortfolioService#getSummary} and {@code NetWorthService#getNetWorth} each do) - otherwise a
   * figure leaks to a caller outside the account's workspace.
   */
  @Transactional(readOnly = true)
  public AccountValuation valueIn(Account account, String targetCurrency, LocalDate asOf) {
    return valuationsOf(List.of(account), List.of(targetCurrency), asOf, false)
        .in(targetCurrency)
        .get(0);
  }

  /**
   * {@link #valueIn} for several accounts in one target currency, resolving each distinct native
   * currency's FX rate once rather than once per account, and every value source in one query for
   * the whole batch (#241 review, #380), not one per account. Same no-access-check contract as
   * {@link #valueIn}: pass only accounts the caller has already been cleared to see.
   */
  @Transactional(readOnly = true)
  public List<AccountValuation> valueAll(
      Collection<Account> accounts, String targetCurrency, LocalDate asOf) {
    return valuationsOf(accounts, List.of(targetCurrency), asOf, false).in(targetCurrency);
  }

  /**
   * US-04-06: {@link #valueAll} in several target currencies at once, plus each account's own
   * currency. Each account's value (and its opening balance and warnings) is resolved once, then
   * only converted per currency, each distinct rate looked up once - the same figures {@link
   * #valueAll} gives per currency, without repeating the value lookups. The number of queries does
   * not depend on the number of accounts (#380). Same no-access-check contract as {@link #valueIn}.
   */
  @Transactional(readOnly = true)
  public MultiCurrencyValuations valueAllIn(
      Collection<Account> accounts, Collection<String> targetCurrencies, LocalDate asOf) {
    return valuationsOf(accounts, targetCurrencies, asOf, true);
  }

  /**
   * {@link #valueAllIn} for a caller that reads only the target currencies (#381 review, the
   * portfolio list): no valuation in each account's own currency is built, and {@link
   * MultiCurrencyValuations#inOwnCurrency} is empty. Same no-access-check contract.
   */
  @Transactional(readOnly = true)
  public MultiCurrencyValuations valueAllInCurrencies(
      Collection<Account> accounts, Collection<String> targetCurrencies, LocalDate asOf) {
    return valuationsOf(accounts, targetCurrencies, asOf, false);
  }

  // The one valuation path. The valuations in each account's own currency are built only when the
  // caller reads them (withOwnCurrency); otherwise MultiCurrencyValuations#inOwnCurrency is empty.
  private MultiCurrencyValuations valuationsOf(
      Collection<Account> accounts,
      Collection<String> targetCurrencies,
      LocalDate asOf,
      boolean withOwnCurrency) {
    Map<String, List<AccountValuation>> byCurrency = new LinkedHashMap<>();
    targetCurrencies.forEach(target -> byCurrency.put(target, new ArrayList<>()));
    if (accounts.isEmpty()) {
      // Nothing to value: no query at all, every list empty (#381 review).
      return new MultiCurrencyValuations(byCurrency, List.of());
    }
    AccountValueSources sources = sourcesFor(accounts, asOf);
    Map<UUID, LocalDate> openingDates = new HashMap<>();
    sources
        .openingBalances()
        .forEach((accountId, opening) -> openingDates.put(accountId, opening.snapshotDate()));
    Map<UUID, List<String>> warnings =
        accountDataQualityService.warningsForAll(accounts, openingDates);

    // Per target currency, one rate per native currency, looked up on first use.
    Map<String, Function<String, Optional<CurrencyConversionResult>>> rates = new LinkedHashMap<>();
    for (String target : byCurrency.keySet()) {
      Function<String, Optional<CurrencyConversionResult>> lookup = rateLookup(target, asOf);
      Map<String, Optional<CurrencyConversionResult>> resolved = new HashMap<>();
      rates.put(target, nativeCurrency -> resolved.computeIfAbsent(nativeCurrency, lookup));
    }
    List<AccountValuation> inOwnCurrency = new ArrayList<>();

    for (Account account : accounts) {
      String ownCurrency = sources.ownCurrencies().get(account.getId());
      Optional<NativeAccountValue> nativeValue = resolveNativeAccountValue(account, sources, asOf);
      List<String> accountWarnings = warnings.getOrDefault(account.getId(), List.of());
      for (Map.Entry<String, Function<String, Optional<CurrencyConversionResult>>> rate :
          rates.entrySet()) {
        byCurrency
            .get(rate.getKey())
            .add(
                inCurrency(
                    account,
                    ownCurrency,
                    rate.getKey(),
                    nativeValue,
                    rate.getValue(),
                    asOf,
                    accountWarnings));
      }
      if (withOwnCurrency) {
        // Its own currency needs no rate.
        inOwnCurrency.add(
            inCurrency(
                account,
                ownCurrency,
                ownCurrency,
                nativeValue,
                nativeCurrency -> Optional.empty(),
                asOf,
                accountWarnings));
      }
    }
    return new MultiCurrencyValuations(byCurrency, inOwnCurrency);
  }

  /**
   * #380: every value source the accounts may need, each kind in one query for all of them (none
   * for a kind no account needs). Each kind is read only for the accounts that {@link
   * #resolveNativeAccountValue} can take it from, decided from the capability flags and from the
   * sources already loaded: a card with an opening balance reads no ledger sum of its own, a loan
   * with a statement no repayments against its principal, an account without a ledger no ledger
   * after its opening balance.
   */
  private AccountValueSources sourcesFor(Collection<Account> accounts, LocalDate asOf) {
    // Kept for an account that holds positions: no value from it yet, but the warnings read it.
    Map<UUID, SnapshotBalance> openingBalances = new HashMap<>();
    List<UUID> openingCandidates = idsOf(accounts, AccountValuationService::takesOpeningBalance);
    if (!openingCandidates.isEmpty()) {
      for (AccountSnapshot snapshot :
          accountSnapshotRepository.findByAccountIdInAndOpeningBalanceTrue(openingCandidates)) {
        openingBalances.put(snapshot.getAccount().getId(), balanceOf(snapshot));
      }
    }

    Map<UUID, NativeAccountValue> latestValuations = new HashMap<>();
    List<UUID> manual = idsOf(accounts, Account::isManualValuation);
    if (!manual.isEmpty()) {
      for (CustomAssetValuation valuation :
          customAssetValuationRepository.findLatestOnOrBefore(manual, asOf)) {
        latestValuations.put(
            valuation.getAccount().getId(),
            new NativeAccountValue(
                valuation.getValue(),
                ValueBasisValues.MANUAL_VALUATION,
                valuation.getValuationDate()));
      }
    }

    // A loan's statement balance, and the value of an account without a ledger (unless it holds
    // positions, which leaves it unknown whatever its snapshots say).
    Map<UUID, SnapshotBalance> latestSnapshots = new HashMap<>();
    List<UUID> snapshotValued =
        idsOf(
            accounts,
            account ->
                account.isHasAmortisation()
                    || (!account.isHasTransactions() && !account.isHoldsPositions()));
    if (!snapshotValued.isEmpty()) {
      for (AccountSnapshot snapshot :
          accountSnapshotRepository.findLatestWithBalance(snapshotValued, asOf)) {
        latestSnapshots.put(snapshot.getAccount().getId(), balanceOf(snapshot));
      }
    }

    List<UUID> loans = idsOf(accounts, Account::isHasAmortisation);
    Set<UUID> unresolvedLoanRepayments =
        loans.isEmpty()
            ? Set.of()
            : Set.copyOf(
                transactionRepository.findAccountIdsWithUnresolvedLoanRepaymentAsOf(loans, asOf));
    // Without a statement, a loan is valued from its original principal and its repayments.
    List<UUID> loansWithoutStatement =
        loans.stream().filter(loan -> !latestSnapshots.containsKey(loan)).toList();
    Map<UUID, BigDecimal> originalPrincipals = originalPrincipalsOf(loansWithoutStatement);

    // A card's own ledger (superseded by its opening balance), and a loan's repayments.
    List<UUID> ledgerSummed = new ArrayList<>(loansWithoutStatement);
    for (Account account : accounts) {
      if (account.isHasStatementCycle()
          && account.isHasTransactions()
          && !openingBalances.containsKey(account.getId())) {
        ledgerSummed.add(account.getId());
      }
    }
    Map<UUID, BigDecimal> ledgerSums =
        ledgerSummed.isEmpty()
            ? Map.of()
            : byKey(transactionRepository.sumAmountByAccountIdsAsOf(ledgerSummed, asOf));

    // The ledger after each opening balance that values its account by then, and after each loan
    // statement.
    List<UUID> snapshotIds = new ArrayList<>();
    List<UUID> statementIds = new ArrayList<>();
    for (Account account : accounts) {
      SnapshotBalance opening = openingBalances.get(account.getId());
      if (opening != null
          && !account.isHoldsPositions()
          && !asOf.isBefore(opening.snapshotDate())) {
        snapshotIds.add(opening.snapshotId());
      }
    }
    for (UUID loan : loans) {
      SnapshotBalance statement = latestSnapshots.get(loan);
      if (statement != null) {
        snapshotIds.add(statement.snapshotId());
        statementIds.add(statement.snapshotId());
      }
    }
    Map<UUID, BigDecimal> ledgerSumsAfterSnapshot =
        snapshotIds.isEmpty()
            ? Map.of()
            : byKey(transactionRepository.sumAmountBookedAfterSnapshots(snapshotIds, asOf));

    // #445: the latest payment on each loan since its statement, which keeps the statement
    // current. Its rows are not all on the loan's ledger (a payment's interest is on the paying
    // account), so it cannot ride along with the ledger sum above.
    Map<UUID, LocalDate> paymentsAfterStatement =
        statementIds.isEmpty()
            ? Map.of()
            : transactionRepository
                .findLatestLoanPaymentDatesAfterStatements(statementIds, asOf)
                .stream()
                .collect(Collectors.toMap(KeyedDate::key, KeyedDate::date));

    return new AccountValueSources(
        accountCurrencyService.ownCurrencies(accounts),
        openingBalances,
        latestSnapshots,
        latestValuations,
        unresolvedLoanRepayments,
        originalPrincipals,
        ledgerSums,
        ledgerSumsAfterSnapshot,
        paymentsAfterStatement);
  }

  // A loan is either a mortgage or a loan, never both: the loan table is read only for the ids
  // without a mortgage row (none at all when every one is a mortgage).
  private Map<UUID, BigDecimal> originalPrincipalsOf(List<UUID> loans) {
    Map<UUID, BigDecimal> originalPrincipals = new HashMap<>();
    if (loans.isEmpty()) {
      return originalPrincipals;
    }
    Set<UUID> mortgages = new HashSet<>();
    for (AccountMortgage mortgage : accountMortgageRepository.findAllById(loans)) {
      mortgages.add(mortgage.getAccountId());
      putIfPresent(originalPrincipals, mortgage.getAccountId(), mortgage.getOriginalPrincipal());
    }
    List<UUID> others = loans.stream().filter(loan -> !mortgages.contains(loan)).toList();
    if (!others.isEmpty()) {
      for (AccountLoan loan : accountLoanRepository.findAllById(others)) {
        putIfPresent(originalPrincipals, loan.getAccountId(), loan.getOriginalPrincipal());
      }
    }
    return originalPrincipals;
  }

  private static List<UUID> idsOf(Collection<Account> accounts, Predicate<Account> filter) {
    return accounts.stream().filter(filter).map(Account::getId).toList();
  }

  private static SnapshotBalance balanceOf(AccountSnapshot snapshot) {
    return new SnapshotBalance(snapshot.getId(), snapshot.getSnapshotDate(), snapshot.getBalance());
  }

  private static void putIfPresent(Map<UUID, BigDecimal> map, UUID key, BigDecimal value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  // A batched sum's rows by key; a key without rows has no entry, as a single sum would be empty.
  private static Map<UUID, BigDecimal> byKey(List<KeyedAmount> rows) {
    Map<UUID, BigDecimal> byKey = new HashMap<>();
    for (KeyedAmount row : rows) {
      putIfPresent(byKey, row.key(), row.amount());
    }
    return byKey;
  }

  // An account with a value source of its own takes no opening balance (OpeningBalanceService),
  // so it is not looked up at all; nor is one without a ledger, valued from its latest snapshot
  // (the opening balance being one) and never warned about rows before it.
  private static boolean takesOpeningBalance(Account account) {
    return !account.isManualValuation()
        && !account.isHasAmortisation()
        && account.isHasTransactions();
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

  // An account's resolved value (or none) as a valuation in targetCurrency: unconverted in its own
  // currency, otherwise at the rate rateForNativeCurrency gives, unknown without one.
  private AccountValuation inCurrency(
      Account account,
      String ownCurrency,
      String targetCurrency,
      Optional<NativeAccountValue> nativeValue,
      Function<String, Optional<CurrencyConversionResult>> rateForNativeCurrency,
      LocalDate asOf,
      List<String> warnings) {
    if (nativeValue.isEmpty()) {
      return unknown(account, ownCurrency, targetCurrency, warnings);
    }
    NativeAccountValue resolved = nativeValue.get();
    boolean sourceStale = isStale(resolved.confirmedOn(), asOf);

    if (ownCurrency.equals(targetCurrency)) {
      // Rounded, not returned raw: resolved.amount() can carry more than money's usual 4 decimal
      // places once it is a card balance summed via fx_rate_to_account_currency (NUMERIC(20,10)) -
      // the same NFR-CALC-007 policy the cross-currency path below already applies via applyRate.
      BigDecimal value =
          resolved.amount().setScale(FxRateService.MONEY_SCALE, RoundingMode.HALF_UP);
      return known(
          account, ownCurrency, targetCurrency, resolved, value, null, sourceStale, warnings);
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
        account,
        ownCurrency,
        targetCurrency,
        resolved,
        convertedValue,
        conversion.get(),
        sourceStale,
        warnings);
  }

  // #380: a value last confirmed longer than app.valuation.snapshot-stale-after before the
  // valuation date - a snapshot or manual valuation by its own date (LATEST_SNAPSHOT,
  // MANUAL_VALUATION), and since #445 a loan statement by its latest payment since, or else its
  // own date: instalments or interest payments keep it current, a loan gone quiet does not. An
  // opening
  // balance plus the ledger after it never goes stale (PO decision on #380, see US-05-06).
  private boolean isStale(LocalDate confirmedOn, LocalDate asOf) {
    return confirmedOn != null && confirmedOn.isBefore(asOf.minus(snapshotStaleAfter));
  }

  // CUSTOM_ASSET (via CustomAssetValuation), MORTGAGE/LOAN (via its newest statement balance and
  // the ledger after it, US-07-21, else original_principal less the principal repaid on its ledger,
  // US-10-02 - see loanValueFromStatement and loanValue), an account without a ledger (via its
  // latest snapshot), any account with an opening balance (US-25-04) and CREDIT_CARD (via its
  // ledger) have a value source today.
  private static Optional<NativeAccountValue> resolveNativeAccountValue(
      Account account, AccountValueSources sources, LocalDate asOf) {
    UUID accountId = account.getId();
    if (account.isManualValuation()) {
      return Optional.ofNullable(sources.latestValuations().get(accountId));
    }
    if (account.isHasAmortisation()) {
      // An UNKNOWN split has no known principal. Reporting the entire payment as principal
      // would overstate net worth, so fail closed until the entry is resolved.
      if (sources.unresolvedLoanRepayments().contains(accountId)) {
        return Optional.empty();
      }
      SnapshotBalance statement = sources.latestSnapshots().get(accountId);
      if (statement != null) {
        return Optional.of(loanValueFromStatement(statement, sources));
      }
      return Optional.ofNullable(sources.originalPrincipals().get(accountId))
          .map(principal -> loanValue(accountId, principal, sources));
    }
    if (!account.isHasTransactions()) {
      return latestSnapshot(account, sources);
    }
    SnapshotBalance openingBalance = sources.openingBalances().get(accountId);
    if (openingBalance != null) {
      return fromOpeningBalance(account, openingBalance, sources, asOf);
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
          Optional.ofNullable(sources.ledgerSums().get(accountId))
              .map(sum -> new NativeAccountValue(sum.negate(), ValueBasisValues.LEDGER))
              .orElseGet(
                  () -> new NativeAccountValue(BigDecimal.ZERO, ValueBasisValues.LEDGER_EMPTY)));
    }
    return Optional.empty();
  }

  /**
   * US-10-02: what is still owed on a loan or mortgage - its original principal less the principal
   * repaid by {@code asOf}. A repayment's incoming leg is a positive row on the loan's ledger
   * (cash-direction signed: money enters the loan), so the repaid principal is the ledger's sum,
   * with voids and reversals left out as for any balance. A loan payment whose split is unknown is
   * on the ledger in full until the member re-enters it; the basis is approximate either way.
   */
  private static NativeAccountValue loanValue(
      UUID accountId, BigDecimal principal, AccountValueSources sources) {
    return Optional.ofNullable(sources.ledgerSums().get(accountId))
        .map(
            repaid ->
                new NativeAccountValue(
                    principal.subtract(repaid),
                    ValueBasisValues.ORIGINAL_PRINCIPAL_LESS_REPAYMENTS))
        .orElseGet(() -> new NativeAccountValue(principal, ValueBasisValues.ORIGINAL_PRINCIPAL));
  }

  /**
   * US-07-21: what is still owed on a loan or mortgage with a statement balance - the newest
   * snapshot on or before {@code asOf} (a loan statement's closing balance, or one the member
   * recorded) less the ledger booked after it, by the same rule as an opening balance ({@link
   * #fromOpeningBalance}): the snapshot holds the positive amount owed, the ledger is
   * cash-direction signed, so an instalment (positive) lowers it and interest charged onto the loan
   * (negative) raises it. Rows on the snapshot's date are contained in it. An exact figure, unlike
   * the original principal less repayments it replaces.
   *
   * <p>#445: it rests on the statement's date, and was last confirmed by the latest payment on the
   * loan since then (an instalment or a payment's interest), or else by the statement itself - so
   * regular payments keep it current, while a loan nothing has been paid on since an old statement
   * goes stale.
   */
  private static NativeAccountValue loanValueFromStatement(
      SnapshotBalance statement, AccountValueSources sources) {
    UUID statementId = statement.snapshotId();
    BigDecimal ledger =
        sources.ledgerSumsAfterSnapshot().getOrDefault(statementId, BigDecimal.ZERO);
    return new NativeAccountValue(
        statement.balance().subtract(ledger),
        ValueBasisValues.LEDGER_FROM_STATEMENT_BALANCE,
        statement.snapshotDate(),
        sources.paymentsAfterStatement().getOrDefault(statementId, statement.snapshotDate()));
  }

  /**
   * #232 review: an account without a ledger ({@code VESTED_BENEFITS}) changes value only through
   * what its provider reports, so its value is its newest snapshot balance on or before {@code
   * asOf} - the opening balance included, being a snapshot too - with that snapshot's date, so a
   * client can show how old it is. Nothing recorded by then: unknown, not zero. An account that
   * {@code holdsPositions} stays unknown, as with an opening balance (EPIC 15).
   */
  private static Optional<NativeAccountValue> latestSnapshot(
      Account account, AccountValueSources sources) {
    if (account.isHoldsPositions()) {
      return Optional.empty();
    }
    return Optional.ofNullable(sources.latestSnapshots().get(account.getId()))
        .map(
            snapshot ->
                new NativeAccountValue(
                    snapshot.balance(), ValueBasisValues.LATEST_SNAPSHOT, snapshot.snapshotDate()));
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
  private static Optional<NativeAccountValue> fromOpeningBalance(
      Account account,
      SnapshotBalance openingBalance,
      AccountValueSources sources,
      LocalDate asOf) {
    if (account.isHoldsPositions() || asOf.isBefore(openingBalance.snapshotDate())) {
      return Optional.empty();
    }
    BigDecimal ledger =
        sources
            .ledgerSumsAfterSnapshot()
            .getOrDefault(openingBalance.snapshotId(), BigDecimal.ZERO);
    BigDecimal value =
        LIABILITY.equals(account.getNature())
            ? openingBalance.balance().subtract(ledger)
            : openingBalance.balance().add(ledger);
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
        false,
        warnings);
  }

  private static AccountValuation known(
      Account account,
      String ownCurrency,
      String targetCurrency,
      NativeAccountValue source,
      BigDecimal value,
      CurrencyConversionResult conversion,
      boolean sourceStale,
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
        sourceStale,
        warnings);
  }
}
