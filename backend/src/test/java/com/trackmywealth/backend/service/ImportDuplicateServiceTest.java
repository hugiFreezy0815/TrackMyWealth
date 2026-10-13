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
import com.trackmywealth.backend.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
  private final ImportDuplicateService service = new ImportDuplicateService(transactionRepository);

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
        return "EUR";
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
    };
  }
}
