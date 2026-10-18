package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportDuplicateCandidate;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.LoanPayment;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.LoanPaymentRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * US-07-04's duplicate rules without a database: reference, the fallback on booking date, amount
 * and currency across sources (US-07-17), and multiplicity. That the two queries read the right
 * ledger rows is {@code ImportBatchControllerTest}'s part.
 */
class ImportDuplicateServiceTest {

  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final LocalDate DAY = LocalDate.of(2019, 1, 5);
  private static final String CSV = "CSV";

  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final LoanPaymentRepository loanPaymentRepository = mock(LoanPaymentRepository.class);
  private final AccountRepository accountRepository = mock(AccountRepository.class);
  private final ImportDuplicateService service =
      new ImportDuplicateService(transactionRepository, loanPaymentRepository, accountRepository);

  // --- rule 4 (#337): a loan payment recorded by hand ----------------------------------------

  @Test
  void aStatementsInstalmentAndInterestAreTheLoanPaymentRecordedByHand() {
    UUID outgoing = UUID.randomUUID();
    UUID incoming = UUID.randomUUID();
    UUID interest = UUID.randomUUID();
    loanPayments(payment(DAY.minusDays(2), "456.78", "123.45", outgoing, interest));
    incomingLeg(incoming, outgoing);
    liveInterest(interest);

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(
                loanRow(1, "DEBT_REPAYMENT", "456.78", DAY),
                loanRow(2, "EXPENSE", "-123.45", DAY),
                loanRow(3, "FEE", "-2.50", DAY)));

    assertThat(duplicates).containsOnly(Map.entry(1, incoming), Map.entry(2, interest));
  }

  @Test
  void anotherTotalOrAPaymentOutsideTheWindowIsNoDuplicate() {
    UUID outgoing = UUID.randomUUID();
    UUID interest = UUID.randomUUID();
    loanPayments(
        payment(DAY, "500.00", "123.45", outgoing, interest),
        payment(DAY.minusDays(6), "456.78", "99.99", UUID.randomUUID(), UUID.randomUUID()));
    incomingLeg(UUID.randomUUID(), outgoing);
    liveInterest(interest);

    assertThat(
            service.findDuplicates(
                ACCOUNT, CSV, List.of(loanRow(1, "DEBT_REPAYMENT", "456.78", DAY))))
        .isEmpty();
  }

  // The payment recorded one row for the statement's two: both are it, or the interest would be
  // charged onto the loan and counted as spending a second time.
  @Test
  void anInterestOnlyPaymentsInstalmentAndInterestBothDuplicateItsInterestRow() {
    UUID interest = UUID.randomUUID();
    loanPayments(payment(DAY, "123.45", "123.45", null, interest));
    liveInterest(interest);

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(
                loanRow(1, "EXPENSE", "-123.45", DAY),
                loanRow(2, "DEBT_REPAYMENT", "123.45", DAY)));

    assertThat(duplicates).containsOnly(Map.entry(1, interest), Map.entry(2, interest));
  }

  // PO (#337): an estimated split is a duplicate all the same, interest and all.
  @Test
  void aPaymentWithAnEstimatedSplitTakesTheStatementsInterestOfAnotherAmount() {
    UUID outgoing = UUID.randomUUID();
    UUID incoming = UUID.randomUUID();
    UUID interest = UUID.randomUUID();
    loanPayments(payment(DAY, "456.78", "120.00", outgoing, interest));
    incomingLeg(incoming, outgoing);
    liveInterest(interest);

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(
                loanRow(1, "EXPENSE", "-123.45", DAY),
                loanRow(2, "DEBT_REPAYMENT", "456.78", DAY)));

    assertThat(duplicates).containsOnly(Map.entry(1, interest), Map.entry(2, incoming));
  }

  @Test
  void interestOfAnotherAmountIsNoDuplicateWithoutThePaymentsInstalment() {
    UUID interest = UUID.randomUUID();
    loanPayments(payment(DAY, "456.78", "120.00", UUID.randomUUID(), interest));
    liveInterest(interest);

    assertThat(service.findDuplicates(ACCOUNT, CSV, List.of(loanRow(1, "EXPENSE", "-123.45", DAY))))
        .isEmpty();
  }

  // Of two interest rows next to a matched instalment, the one of the payment's amount is its.
  @Test
  void aPaymentTakesTheInterestRowOfItsAmountFirstAndOnlyOne() {
    UUID outgoing = UUID.randomUUID();
    UUID incoming = UUID.randomUUID();
    UUID interest = UUID.randomUUID();
    loanPayments(payment(DAY, "456.78", "123.45", outgoing, interest));
    incomingLeg(incoming, outgoing);
    liveInterest(interest);

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(
                loanRow(1, "DEBT_REPAYMENT", "456.78", DAY),
                loanRow(2, "EXPENSE", "-9.99", DAY),
                loanRow(3, "EXPENSE", "-123.45", DAY.plusDays(1))));

    assertThat(duplicates).containsOnly(Map.entry(1, incoming), Map.entry(3, interest));
  }

  @Test
  void ofTwoEqualPaymentsTheNearestIsTheInstalmentsAndEachIsTakenOnce() {
    UUID nearOutgoing = UUID.randomUUID();
    UUID farOutgoing = UUID.randomUUID();
    UUID near = UUID.randomUUID();
    UUID far = UUID.randomUUID();
    loanPayments(
        payment(DAY.minusDays(4), "456.78", "123.45", farOutgoing, UUID.randomUUID()),
        payment(DAY.minusDays(1), "456.78", "123.45", nearOutgoing, UUID.randomUUID()));
    incomingLegs(leg(near, nearOutgoing), leg(far, farOutgoing));

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(
                loanRow(1, "DEBT_REPAYMENT", "456.78", DAY),
                loanRow(2, "DEBT_REPAYMENT", "456.78", DAY),
                loanRow(3, "DEBT_REPAYMENT", "456.78", DAY)));

    assertThat(duplicates).containsOnly(Map.entry(1, near), Map.entry(2, far));
  }

  @Test
  void aPaymentInAnotherCurrencyIsNoDuplicate() {
    UUID outgoing = UUID.randomUUID();
    LoanPayment euros = payment(DAY, "456.78", "123.45", outgoing, UUID.randomUUID());
    euros.setCurrency("EUR");
    loanPayments(euros);
    incomingLeg(UUID.randomUUID(), outgoing);

    assertThat(
            service.findDuplicates(
                ACCOUNT, CSV, List.of(loanRow(1, "DEBT_REPAYMENT", "456.78", DAY))))
        .isEmpty();
  }

  // A payment whose interest row was voided matches nothing through it.
  @Test
  void aRemovedInterestRowIsNoDuplicate() {
    loanPayments(payment(DAY, "123.45", "123.45", null, UUID.randomUUID()));
    when(transactionRepository.findLiveIdsAmong(anyCollection())).thenReturn(List.of());

    assertThat(
            service.findDuplicates(
                ACCOUNT,
                CSV,
                List.of(
                    loanRow(1, "DEBT_REPAYMENT", "123.45", DAY),
                    loanRow(2, "EXPENSE", "-123.45", DAY))))
        .isEmpty();
  }

  // The commit's re-check (#287): a payment's row another row of the batch holds stays its.
  @Test
  void aReservedRowOfAPaymentIsNoOtherRowsDuplicate() {
    UUID outgoing = UUID.randomUUID();
    UUID incoming = UUID.randomUUID();
    UUID interest = UUID.randomUUID();
    loanPayments(payment(DAY, "456.78", "123.45", outgoing, interest));
    incomingLeg(incoming, outgoing);
    liveInterest(interest);

    assertThat(
            service.findDuplicates(
                ACCOUNT,
                CSV,
                List.of(
                    loanRow(1, "DEBT_REPAYMENT", "456.78", DAY),
                    loanRow(2, "EXPENSE", "-123.45", DAY)),
                Set.of(incoming, interest)))
        .isEmpty();
  }

  @Test
  void rowsThatAreNoInstalmentOrInterestNeverLookForLoanPayments() {
    service.findDuplicates(ACCOUNT, CSV, List.of(row(1, "12.40", "Refund", null)));

    verify(loanPaymentRepository, never())
        .findInEffectByLoanAccountIdBookedBetween(any(), any(), any());
  }

  // A current account's expenses look like interest; it takes no loan payment, so none is read.
  @Test
  void anAccountWithoutAmortisationNeverLooksForLoanPayments() {
    Account current = new Account();
    current.setHasAmortisation(false);
    when(accountRepository.findById(ACCOUNT)).thenReturn(Optional.of(current));

    service.findDuplicates(ACCOUNT, CSV, List.of(loanRow(1, "EXPENSE", "-123.45", DAY)));

    verify(loanPaymentRepository, never())
        .findInEffectByLoanAccountIdBookedBetween(any(), any(), any());
  }

  private void loanPayments(LoanPayment... payments) {
    Account loan = new Account();
    loan.setHasAmortisation(true);
    when(accountRepository.findById(ACCOUNT)).thenReturn(Optional.of(loan));
    when(loanPaymentRepository.findInEffectByLoanAccountIdBookedBetween(eq(ACCOUNT), any(), any()))
        .thenReturn(List.of(payments));
  }

  private void liveInterest(UUID... ids) {
    when(transactionRepository.findLiveIdsAmong(anyCollection())).thenReturn(List.of(ids));
  }

  private void incomingLeg(UUID id, UUID outgoing) {
    incomingLegs(leg(id, outgoing));
  }

  private void incomingLegs(Transaction... legs) {
    when(transactionRepository.findByRelatedTransactionIdIn(anyCollection()))
        .thenReturn(List.of(legs));
  }

  private static Transaction leg(UUID id, UUID outgoing) {
    Account loan = mock(Account.class);
    when(loan.getId()).thenReturn(ACCOUNT);
    Transaction leg = mock(Transaction.class);
    when(leg.getId()).thenReturn(id);
    when(leg.getAccount()).thenReturn(loan);
    when(leg.getTransactionType()).thenReturn("DEBT_REPAYMENT");
    when(leg.getRelatedTransactionId()).thenReturn(outgoing);
    return leg;
  }

  private static LoanPayment payment(
      LocalDate booked, String total, String interest, UUID outgoing, UUID interestRow) {
    LoanPayment payment = new LoanPayment();
    payment.setBookingDate(booked);
    payment.setCurrency("CHF");
    payment.setTotalAmount(new BigDecimal(total));
    payment.setInterestAmount(new BigDecimal(interest));
    payment.setPrincipalAmount(new BigDecimal(total).subtract(new BigDecimal(interest)));
    payment.setPrincipalTransactionId(outgoing);
    payment.setInterestTransactionId(interestRow);
    return payment;
  }

  private static ParsedImportRow loanRow(int number, String type, String amount, LocalDate day) {
    return ParsedImportRow.parsed(
        number,
        Map.of(),
        new CanonicalImportRow(
            day,
            null,
            new BigDecimal(amount),
            "CHF",
            type,
            "Darlehen",
            null,
            null,
            null,
            null,
            null));
  }

  @Test
  void aRowWithAReferenceTheAccountAlreadyHoldsIsADuplicateOfThatRow() {
    UUID existing = UUID.randomUUID();
    when(transactionRepository.findByExternalIds(eq(ACCOUNT), eq(CSV), anyCollection()))
        .thenReturn(List.of(candidate(existing, "-12.40", "Bakery", CSV, "REF-1")));

    Map<Integer, UUID> duplicates =
        service.findDuplicates(ACCOUNT, CSV, List.of(row(1, "-12.40", "Bakery", "REF-1")));

    assertThat(duplicates).containsExactly(Map.entry(1, existing));
  }

  @Test
  void aForeignCardPurchaseWithoutAReferenceUsesTheLedgerCurrencyForDuplicateMatching() {
    UUID existing = UUID.randomUUID();
    ledger(candidate(existing, "-50.00", "Hotel", CSV, null));
    CanonicalImportRow purchase =
        new CanonicalImportRow(
            DAY,
            null,
            new BigDecimal("-55.00"),
            "CHF",
            "CREDIT_CARD_PURCHASE",
            "Hotel",
            null,
            null,
            null,
            null,
            null,
            new BigDecimal("-50.00"),
            "EUR",
            new BigDecimal("0.909091"));
    ParsedImportRow row = ParsedImportRow.parsed(1, Map.of(), purchase);

    assertThat(service.findDuplicates(ACCOUNT, CSV, List.of(row)))
        .containsExactly(Map.entry(1, existing));
  }

  @Test
  void theExactFallbackFindsAManualEntryWhateverItsCaseAndBlanks() {
    UUID manual = UUID.randomUUID();
    ledger(candidate(manual, "-12.4", "  bakery\u00a0 MUSTERHAUSEN ", "MANUAL", null));

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT, CSV, List.of(row(1, "-12.40", "Bakery Musterhausen", null)));

    assertThat(duplicates).containsExactly(Map.entry(1, manual));
  }

  @Test
  void aBookingAlreadyImportedFromAnotherSourceIsFoundDespiteItsReferenceAndWording() {
    // The same booking from a PDF statement first, now from the CSV export with its reference.
    UUID fromPdf = UUID.randomUUID();
    ledger(candidate(fromPdf, "-12.40", "BAECKEREI MUSTER GMBH", "DOCUMENT", "PDF-REF"));

    Map<Integer, UUID> duplicates =
        service.findDuplicates(ACCOUNT, CSV, List.of(row(1, "-12.40", "Bakery", "REF-1")));

    assertThat(duplicates).containsExactly(Map.entry(1, fromPdf));
  }

  @Test
  void aRowOfTheSameSourceWithAnotherReferenceIsAnotherBooking() {
    ledger(candidate(UUID.randomUUID(), "-12.40", "Bakery", CSV, "REF-0"));

    assertThat(service.findDuplicates(ACCOUNT, CSV, List.of(row(1, "-12.40", "Bakery", "REF-1"))))
        .isEmpty();
  }

  @Test
  void aRowOfTheSameSourceWithoutAReferenceCanStillBeTheSameBooking() {
    UUID unreferenced = UUID.randomUUID();
    ledger(candidate(unreferenced, "-12.40", "Bakery", CSV, null));

    assertThat(service.findDuplicates(ACCOUNT, CSV, List.of(row(1, "-12.40", "Bakery", "REF-1"))))
        .containsExactly(Map.entry(1, unreferenced));
  }

  @Test
  void twoIdenticalRowsAgainstOneIdenticalLedgerRowGiveExactlyOneDuplicate() {
    UUID coffee = UUID.randomUUID();
    ledger(candidate(coffee, "-3.50", "Coffee", "MANUAL", null));

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(row(1, "-3.50", "Coffee", null), row(2, "-3.50", "Coffee", null)));

    assertThat(duplicates).containsExactly(Map.entry(1, coffee));
  }

  @Test
  void aLedgerRowMatchedByReferenceIsNotMatchedAgainByTheFallback() {
    UUID existing = UUID.randomUUID();
    ImportDuplicateCandidate referenced = candidate(existing, "-3.50", "Coffee", CSV, "REF-1");
    when(transactionRepository.findByExternalIds(eq(ACCOUNT), eq(CSV), anyCollection()))
        .thenReturn(List.of(referenced));
    ledger(referenced);

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(row(1, "-3.50", "Coffee", "REF-1"), row(2, "-3.50", "Coffee", null)));

    assertThat(duplicates).containsExactly(Map.entry(1, existing));
  }

  @Test
  void aLedgerRowAnotherRowOfTheBatchHoldsIsNoMatchForTheRest() {
    // #287: the commit re-checks only rows without a live duplicate; those keep their ledger rows.
    UUID held = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    ledger(
        candidate(held, "-3.50", "Coffee", "MANUAL", null),
        candidate(second, "-3.50", "Coffee", "MANUAL", null));

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(row(2, "-3.50", "Coffee", null), row(3, "-3.50", "Coffee", null)),
            Set.of(held));

    assertThat(duplicates).containsExactly(Map.entry(2, second));
  }

  @Test
  void aReservedLedgerRowIsNoMatchByReferenceEither() {
    UUID held = UUID.randomUUID();
    when(transactionRepository.findByExternalIds(eq(ACCOUNT), eq(CSV), anyCollection()))
        .thenReturn(List.of(candidate(held, "-12.40", "Bakery", CSV, "REF-1")));

    assertThat(
            service.findDuplicates(
                ACCOUNT, CSV, List.of(row(1, "-12.40", "Bakery", "REF-1")), Set.of(held)))
        .isEmpty();
  }

  @Test
  void anotherDateAmountOrCurrencyIsNoMatch() {
    ledger(candidate(UUID.randomUUID(), "-12.40", "Bakery", "MANUAL", null));

    List<ParsedImportRow> rows =
        List.of(
            row(1, DAY.plusDays(1), "-12.40", "EUR", "Bakery"),
            row(2, DAY, "-12.41", "EUR", "Bakery"),
            row(3, DAY, "-12.40", "CHF", "Bakery"));

    assertThat(service.findDuplicates(ACCOUNT, CSV, rows)).isEmpty();
  }

  @Test
  void anotherDescriptionIsStillTheSameBooking() {
    // US-07-17: the CAMT and MT940 exports of one account word the same booking differently.
    UUID shop = UUID.randomUUID();
    ledger(candidate(shop, "-47.35", "Shop A", CSV, null));

    assertThat(
            service.findDuplicates(
                ACCOUNT, CSV, List.of(row(1, DAY, "-47.35", "EUR", "SHOP A FILIALE 12"))))
        .containsExactly(Map.entry(1, shop));
  }

  @Test
  void twoRowsAgainstOneBookingOfAnotherWordingGiveExactlyOneDuplicate() {
    UUID coffee = UUID.randomUUID();
    ledger(candidate(coffee, "-3.50", "Coffee", "MANUAL", null));

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(row(1, "-3.50", "Kiosk", null), row(2, "-3.50", "Cafe Central", null)));

    assertThat(duplicates).containsExactly(Map.entry(1, coffee));
  }

  @Test
  void whereSeveralBookingsMatchARowIsPairedWithTheOneOfItsDescription() {
    UUID coffeeA = UUID.randomUUID();
    UUID coffeeB = UUID.randomUUID();
    ledger(
        candidate(coffeeA, "-3.50", "Coffee A", "MANUAL", null),
        candidate(coffeeB, "-3.50", "Coffee B", "MANUAL", null));

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(row(1, "-3.50", " COFFEE  b", null), row(2, "-3.50", "Coffee A", null)));

    assertThat(duplicates).containsEntry(1, coffeeB).containsEntry(2, coffeeA).hasSize(2);
  }

  @Test
  void aRowWithoutADescriptionMatchTakesABookingNoOtherRowSharesTheWordingOf() {
    // Row 2 shares its wording with the first booking, so row 1 gets the second one, although it
    // comes first in the file.
    UUID coffeeA = UUID.randomUUID();
    UUID coffeeB = UUID.randomUUID();
    ledger(
        candidate(coffeeA, "-3.50", "Coffee A", "MANUAL", null),
        candidate(coffeeB, "-3.50", "Coffee B", "MANUAL", null));

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(row(1, "-3.50", "Kiosk", null), row(2, "-3.50", "Coffee A", null)));

    assertThat(duplicates).containsEntry(1, coffeeB).containsEntry(2, coffeeA).hasSize(2);
  }

  @Test
  void aDescriptionMatchDoesNotTakeTheOnlyBookingAReferencedRowMayMatch() {
    // Row 1 shares its wording with the manual booking, but could also be the referenced CSV one;
    // row 2 carries another reference of the same source, so only the manual booking fits it.
    UUID referenced = UUID.randomUUID();
    UUID manual = UUID.randomUUID();
    ledger(
        candidate(referenced, "-12.40", "Bakery", CSV, "REF-0"),
        candidate(manual, "-12.40", "Coffee", "MANUAL", null));

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(row(1, "-12.40", "Coffee", null), row(2, "-12.40", "Bakery", "REF-2")));

    assertThat(duplicates).containsEntry(1, referenced).containsEntry(2, manual).hasSize(2);
  }

  @Test
  void aSameSourceBookingWithAnotherReferenceIsSkippedForAMatchingOne() {
    UUID unreferenced = UUID.randomUUID();
    ledger(
        candidate(UUID.randomUUID(), "-12.40", "Bakery", CSV, "REF-0"),
        candidate(unreferenced, "-12.40", "Other wording", CSV, null));

    assertThat(service.findDuplicates(ACCOUNT, CSV, List.of(row(1, "-12.40", "Bakery", "REF-1"))))
        .containsExactly(Map.entry(1, unreferenced));
  }

  @Test
  void aRowWithoutDescriptionMatchesAnEmptyOneAndFallsBackToItsCounterparty() {
    UUID empty = UUID.randomUUID();
    UUID counterparty = UUID.randomUUID();
    ledger(
        candidate(empty, "-1.00", null, "MANUAL", null),
        candidate(counterparty, "-2.00", "Stadtwerke", "MANUAL", null));
    CanonicalImportRow fromCounterparty =
        new CanonicalImportRow(
            DAY,
            null,
            new BigDecimal("-2.00"),
            "EUR",
            "EXPENSE",
            null,
            "Stadtwerke",
            null,
            null,
            null,
            null);

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(
                row(1, DAY, "-1.00", "EUR", " "),
                ParsedImportRow.parsed(2, Map.of(), fromCounterparty)));

    assertThat(duplicates).containsEntry(1, empty).containsEntry(2, counterparty);
  }

  @Test
  void noRowsReadNothing() {
    assertThat(service.findDuplicates(ACCOUNT, CSV, List.of())).isEmpty();
    verify(transactionRepository, never()).findLiveForDuplicateCheck(any(), any(), any());
  }

  @Test
  void theLedgerIsReadOnceForTheBatchsDateRange() {
    ledger();

    service.findDuplicates(
        ACCOUNT,
        CSV,
        List.of(
            row(1, DAY.plusDays(3), "-1.00", "EUR", "a"),
            row(2, DAY, "-1.00", "EUR", "b"),
            row(3, DAY.plusDays(1), "-1.00", "EUR", "c")));

    verify(transactionRepository).findLiveForDuplicateCheck(ACCOUNT, DAY, DAY.plusDays(3));
  }

  // #341: the same purchase booked in euros (template version 1, or by hand) and in its original
  // currency (version 2) is one booking, whichever of the two the ledger holds.
  @Test
  void aForeignPurchaseIsADuplicateOfTheSamePurchaseBookedInTheAccountsCurrency() {
    UUID inEuros = UUID.randomUUID();
    ledger(candidate(inEuros, "-1.4900", "Muster Online Shop", CSV, null));

    assertThat(
            service.findDuplicates(
                ACCOUNT, CSV, List.of(foreignPurchase(1, "-1.49", "-37.00", "CZK"))))
        .containsExactly(Map.entry(1, inEuros));
  }

  @Test
  void aRowInTheAccountsCurrencyIsADuplicateOfTheSamePurchaseBookedInItsOriginalCurrency() {
    UUID inKoruna = UUID.randomUUID();
    // -37.00 CZK at the rate derived from the billed -1.49 EUR: -1.4900000011, -1.49 to the cent.
    ledger(candidate(inKoruna, "-37.0000", "CZK", "0.0402702703", false, "Shop", "MANUAL", null));

    assertThat(service.findDuplicates(ACCOUNT, CSV, List.of(row(1, "-1.49", "Shop", null))))
        .containsExactly(Map.entry(1, inKoruna));
  }

  @Test
  void aLedgerRowAtAnEstimatedRateIsNoCrossCurrencyMatch() {
    ledger(
        candidate(
            UUID.randomUUID(), "-37.0000", "CZK", "0.0402702703", true, "Shop", "MANUAL", null));

    assertThat(service.findDuplicates(ACCOUNT, CSV, List.of(row(1, "-1.49", "Shop", null))))
        .isEmpty();
  }

  @Test
  void twoForeignPurchasesBilledAlikeAreNotPairedAcrossCurrencies() {
    // Booked in CZK at a derived rate, billed -1.49 EUR: another purchase than 40.00 PLN.
    ledger(
        candidate(UUID.randomUUID(), "-37.0000", "CZK", "0.0402702703", false, "Shop", CSV, null));

    assertThat(
            service.findDuplicates(
                ACCOUNT, CSV, List.of(foreignPurchase(1, "-1.49", "-40.00", "PLN"))))
        .isEmpty();
  }

  @Test
  void aLedgerRowPairedExactlyIsNotPairedAgainAcrossCurrencies() {
    UUID inEuros = UUID.randomUUID();
    ledger(candidate(inEuros, "-1.49", "Shop", CSV, null));

    // The euro row takes the euro booking; the foreign purchase billed alike is a booking of its
    // own.
    assertThat(
            service.findDuplicates(
                ACCOUNT,
                CSV,
                List.of(
                    row(1, "-1.49", "Shop", null), foreignPurchase(2, "-1.49", "-37.00", "CZK"))))
        .containsExactly(Map.entry(1, inEuros));
  }

  @Test
  void aReservedLedgerRowIsNoCrossCurrencyMatchEither() {
    // #287: the commit's re-check; another row of the batch holds the euro booking already.
    UUID held = UUID.randomUUID();
    ledger(candidate(held, "-1.49", "Muster Online Shop", CSV, null));

    assertThat(
            service.findDuplicates(
                ACCOUNT, CSV, List.of(foreignPurchase(1, "-1.49", "-37.00", "CZK")), Set.of(held)))
        .isEmpty();
  }

  @Test
  void twoForeignPurchasesAgainstOneBookingInEurosGiveExactlyOneDuplicate() {
    UUID inEuros = UUID.randomUUID();
    ledger(candidate(inEuros, "-1.49", "Muster Online Shop", CSV, null));

    Map<Integer, UUID> duplicates =
        service.findDuplicates(
            ACCOUNT,
            CSV,
            List.of(
                foreignPurchase(1, "-1.49", "-37.00", "CZK"),
                foreignPurchase(2, "-1.49", "-37.00", "CZK")));

    assertThat(duplicates).containsExactly(Map.entry(1, inEuros));
  }

  @Test
  void theConvertedAmountIsRoundedHalfUpToTheCent() {
    // -10.00 CZK at 0.1235 is -1.2350: -1.24 to the cent, never -1.23.
    UUID inKoruna = UUID.randomUUID();
    ledger(candidate(inKoruna, "-10.00", "CZK", "0.1235", false, "Shop", "MANUAL", null));

    assertThat(service.findDuplicates(ACCOUNT, CSV, List.of(row(1, "-1.24", "Shop", null))))
        .containsExactly(Map.entry(1, inKoruna));
    assertThat(service.findDuplicates(ACCOUNT, CSV, List.of(row(1, "-1.23", "Shop", null))))
        .isEmpty();
  }

  // --- fixtures --------------------------------------------------------------------------------

  private void ledger(ImportDuplicateCandidate... candidates) {
    when(transactionRepository.findLiveForDuplicateCheck(eq(ACCOUNT), any(), any()))
        .thenReturn(List.of(candidates));
  }

  private static ParsedImportRow row(
      int rowNumber, String amount, String description, String externalId) {
    return ParsedImportRow.parsed(
        rowNumber,
        Map.of(),
        new CanonicalImportRow(
            DAY,
            null,
            new BigDecimal(amount),
            "EUR",
            "EXPENSE",
            description,
            null,
            externalId,
            null,
            null,
            null));
  }

  private static ParsedImportRow row(
      int rowNumber, LocalDate date, String amount, String currency, String description) {
    return ParsedImportRow.parsed(
        rowNumber,
        Map.of(),
        new CanonicalImportRow(
            date,
            null,
            new BigDecimal(amount),
            currency,
            "EXPENSE",
            description,
            null,
            null,
            null,
            null,
            null));
  }

  private static ImportDuplicateCandidate candidate(
      UUID id, String amount, String description, String source, String externalId) {
    return candidate(id, amount, "EUR", null, false, description, source, externalId);
  }

  // A ledger row on an EUR account in {@code currency}, at {@code rate} to EUR.
  private static ImportDuplicateCandidate candidate(
      UUID id,
      String amount,
      String currency,
      String rate,
      boolean estimated,
      String description,
      String source,
      String externalId) {
    return new ImportDuplicateCandidate() {
      @Override
      public UUID getId() {
        return id;
      }

      @Override
      public LocalDate getBookingDate() {
        return DAY;
      }

      @Override
      public BigDecimal getAmount() {
        return new BigDecimal(amount);
      }

      @Override
      public String getCurrency() {
        return currency;
      }

      @Override
      public String getMerchantDescription() {
        return description;
      }

      @Override
      public String getSource() {
        return source;
      }

      @Override
      public String getExternalId() {
        return externalId;
      }

      @Override
      public BigDecimal getFxRateToAccountCurrency() {
        return rate == null ? null : new BigDecimal(rate);
      }

      @Override
      public Boolean getFxRateEstimated() {
        return estimated;
      }

      @Override
      public String getAccountCurrency() {
        return "EUR";
      }
    };
  }

  // A card purchase of {@code billed} EUR for {@code original} in {@code originalCurrency}.
  private static ParsedImportRow foreignPurchase(
      int rowNumber, String billed, String original, String originalCurrency) {
    return ParsedImportRow.parsed(
        rowNumber,
        Map.of(),
        new CanonicalImportRow(
            DAY,
            null,
            new BigDecimal(billed),
            "EUR",
            "CREDIT_CARD_PURCHASE",
            "Muster Online Shop",
            null,
            null,
            null,
            null,
            null,
            new BigDecimal(original),
            originalCurrency,
            null,
            false));
  }
}
