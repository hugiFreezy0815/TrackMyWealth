package com.trackmywealth.backend.service;

import static com.trackmywealth.backend.testsupport.CamtFixtures.IBAN;
import static com.trackmywealth.backend.testsupport.CamtFixtures.IBAN_NORMALIZED;
import static com.trackmywealth.backend.testsupport.CamtFixtures.OTHER_IBAN;
import static com.trackmywealth.backend.testsupport.CamtFixtures.camt052;
import static com.trackmywealth.backend.testsupport.CamtFixtures.camt053;
import static com.trackmywealth.backend.testsupport.CamtFixtures.detail;
import static com.trackmywealth.backend.testsupport.CamtFixtures.entry;
import static com.trackmywealth.backend.testsupport.CamtFixtures.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.trackmywealth.backend.camt.CamtXmlReader;
import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportAccountContext;
import com.trackmywealth.backend.dto.ImportParseResult;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ImportStatementBalances;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import com.trackmywealth.backend.testsupport.CamtFixtures;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #350: what a CAMT statement imports - rows, references, account and closing balance - without a
 * template. Every statement is synthetic ({@link CamtFixtures}).
 */
class CamtImportReaderServiceTest {

  private static final ImportAccountContext CASH = new ImportAccountContext("EUR", false, false);
  private static final ImportAccountContext CARD = new ImportAccountContext("EUR", true, false);
  private static final ImportAccountContext LOAN = new ImportAccountContext("EUR", false, true);
  private static final String DAY = "2031-01-25";

  private final CamtImportReaderService service =
      new CamtImportReaderService(new BusinessDateService(Clock.systemUTC(), "Europe/Zurich"));

  // --- entries -----------------------------------------------------------------------------------

  @Test
  void importsBookedEntriesSignedByTheirDirectionAndShowsTheOthersWithoutImportingThem() {
    ImportParseResult result =
        parse(
            CASH,
            entry("12.34", "DBIT", DAY),
            entry("100.00", "CRDT", DAY).valueDate("2031-01-26"),
            entry("3.00", "CRDT", DAY).status("PDNG"),
            entry("4.00", "DBIT", DAY).status("INFO"),
            entry("0.00", "CRDT", DAY));

    assertThat(result.rows())
        .extracting(ParsedImportRow::rowNumber, ParsedImportRow::status, ParsedImportRow::errorCode)
        .containsExactly(
            tuple(1, ImportRowErrorValues.STATUS_PARSED, null),
            tuple(2, ImportRowErrorValues.STATUS_PARSED, null),
            tuple(3, ImportRowErrorValues.STATUS_SKIPPED, ImportRowErrorValues.CAMT_NOT_BOOKED),
            tuple(4, ImportRowErrorValues.STATUS_SKIPPED, ImportRowErrorValues.CAMT_NOT_BOOKED),
            tuple(5, ImportRowErrorValues.STATUS_SKIPPED, ImportRowErrorValues.ZERO_AMOUNT));
    assertThat(result.rows().get(0).canonical())
        .extracting(
            CanonicalImportRow::amount,
            CanonicalImportRow::currency,
            CanonicalImportRow::transactionType,
            CanonicalImportRow::bookingDate,
            CanonicalImportRow::valueDate)
        .containsExactly(new BigDecimal("-12.34"), "EUR", "EXPENSE", LocalDate.parse(DAY), null);
    assertThat(result.rows().get(1).canonical().transactionType()).isEqualTo("INCOME");
    assertThat(result.rows().get(1).canonical().valueDate()).isEqualTo(LocalDate.of(2031, 1, 26));
    assertThat(result.rows().get(2).errorArgs()).containsExactly(Map.entry("status", "PDNG"));
    assertThat(result.rows().get(2).canonical()).isNull();
  }

  @Test
  void typesRowsByTheAccountAndAPayingBackReversalAsARefund() {
    assertThat(types(CARD, entry("9.99", "DBIT", DAY), entry("50.00", "CRDT", DAY)))
        .containsExactly("CREDIT_CARD_PURCHASE", "SETTLEMENT");
    assertThat(types(LOAN, entry("80.00", "DBIT", DAY), entry("500.00", "CRDT", DAY)))
        .containsExactly("EXPENSE", "DEBT_REPAYMENT");
    // RvslInd: the sign still follows CdtDbtInd (spec 6.5.1).
    assertThat(
            types(
                CASH,
                entry("9.99", "CRDT", DAY).reversal("true"),
                entry("9.99", "DBIT", DAY).reversal("true")))
        .containsExactly("REFUND", "EXPENSE");
    assertThat(types(CARD, entry("9.99", "CRDT", DAY).reversal("1"))).containsExactly("REFUND");
    assertThat(types(LOAN, entry("9.99", "CRDT", DAY).reversal("true")))
        .containsExactly("DEBT_REPAYMENT");

    ParsedImportRow reversal =
        parse(CASH, entry("9.99", "CRDT", DAY).reversal("true")).rows().get(0);
    assertThat(reversal.rawData()).containsEntry(CamtImportReaderService.CELL_REVERSAL, "true");
  }

  // --- references --------------------------------------------------------------------------------

  @Test
  void scopesTheBanksReferenceByItsKindAndBookingAndIgnoresNoReference() {
    ImportParseResult result =
        parse(
            CASH,
            entry("12.30", "DBIT", DAY).reference("REF-1"),
            entry("12.30", "DBIT", DAY).reference("NOTPROVIDED"),
            entry("5.00", "CRDT", DAY).reference("nonref"),
            entry("5.00", "CRDT", DAY).reference("R".repeat(36)),
            // An entry without a reference takes its only detail's.
            entry("7.00", "CRDT", DAY).detail(detail("7.00", "CRDT").reference("TX-9")),
            // EndToEndId is the originator's: no booking id.
            entry("8.00", "CRDT", DAY).detail(detail("8.00", "CRDT").endToEndId("E2E-1")));

    assertThat(result.rows())
        .extracting(row -> row.canonical().externalId())
        .containsExactly(
            "CAMT:NTRY:2031-01-25:-12.3:EUR:REF-1",
            null,
            null,
            null,
            "CAMT:TX:2031-01-25:7:EUR:TX-9",
            null);
  }

  // A bank that reuses a reference names two bookings with two ids; the same booking twice (one
  // day's camt.052 and camt.053 in one archive) has one id, so staging keeps only its first row.
  @Test
  void aReusedReferenceNamesEachBookingApart() {
    ImportParseResult result =
        parse(
            CASH,
            entry("10.00", "DBIT", DAY).reference("SAME"),
            entry("11.00", "DBIT", DAY).reference("SAME"),
            entry("10.00", "DBIT", "2031-01-26").reference("SAME"),
            entry("10.00", "DBIT", DAY).reference("SAME"));

    assertThat(result.rows())
        .extracting(row -> row.canonical().externalId())
        .containsExactly(
            "CAMT:NTRY:2031-01-25:-10:EUR:SAME",
            "CAMT:NTRY:2031-01-25:-11:EUR:SAME",
            "CAMT:NTRY:2031-01-26:-10:EUR:SAME",
            "CAMT:NTRY:2031-01-25:-10:EUR:SAME");
  }

  // --- collective entries ------------------------------------------------------------------------

  @Test
  void splitsACollectiveEntryWhoseDetailsReconcileIntoOneRowEach() {
    ImportParseResult result =
        parse(
            CASH,
            entry("70.00", "DBIT", DAY)
                .reference("ENTRY-REF")
                .detail(detail("50.00", "DBIT").reference("TX-1").creditor("Vermieter AG"))
                .detail(detail("25.00", "DBIT").creditor("Stadtwerke Muster"))
                .detail(detail("5.00", "CRDT").debtor("Erstattung Muster")));

    assertThat(result.rows())
        .extracting(
            row -> row.canonical().amount(),
            row -> row.canonical().transactionType(),
            row -> row.canonical().counterpartyName(),
            row -> row.canonical().externalId(),
            row -> row.rawData().get(CamtImportReaderService.CELL_TRANSACTION))
        .containsExactly(
            tuple(
                new BigDecimal("-50.00"),
                "EXPENSE",
                "Vermieter AG",
                "CAMT:TX:2031-01-25:-50:EUR:TX-1",
                "1/3"),
            // The entry's reference never names one of its details.
            tuple(new BigDecimal("-25.00"), "EXPENSE", "Stadtwerke Muster", null, "2/3"),
            tuple(new BigDecimal("5.00"), "INCOME", "Erstattung Muster", null, "3/3"));
  }

  // Before .001.04 a detail has no direction: the entry's.
  @Test
  void aDetailOfVersion02TakesTheEntrysDirection() {
    byte[] statement =
        camt053("001.02")
            .statement(
                IBAN,
                s ->
                    s.entry(
                        entry("30.00", "DBIT", DAY)
                            .detail(detail("10.00", null))
                            .detail(detail("20.00", null))))
            .bytes();

    assertThat(service.parse(statement, CASH).rows())
        .extracting(row -> row.canonical().amount())
        .containsExactly(new BigDecimal("-10.00"), new BigDecimal("-20.00"));
  }

  @Test
  void refusesToSplitDetailsThatDoNotAddUpOrAreInAnotherCurrency() {
    ImportParseResult result =
        parse(
            CASH,
            entry("8.00", "CRDT", DAY)
                .detail(detail("3.00", "CRDT"))
                .detail(detail("4.00", "CRDT")),
            entry("8.00", "CRDT", DAY)
                .detail(detail("4.00", "CRDT"))
                .detail(detail("4.00", "CRDT").currency("CHF")));

    assertThat(result.rows()).hasSize(2);
    assertThat(result.rows().get(0).errorCode())
        .isEqualTo(ImportRowErrorValues.CAMT_DETAILS_MISMATCH);
    assertThat(result.rows().get(0).errorArgs())
        .containsExactly(Map.entry("entryAmount", "8.00"), Map.entry("detailTotal", "7.00"));
    assertThat(result.rows().get(1).errorCode())
        .isEqualTo(ImportRowErrorValues.CAMT_DETAILS_CURRENCY);
    assertThat(result.rows().get(1).errorArgs())
        .containsExactly(Map.entry("entryCurrency", "EUR"), Map.entry("detailCurrency", "CHF"));
    assertThat(result.rows()).allSatisfy(row -> assertThat(row.canonical()).isNull());
  }

  // Details missing, or more than were read: the booked amount is still exact, so it is one row.
  @Test
  void importsAnEntryWithIncompleteDetailsWholeAtItsBookedAmount() {
    ImportParseResult result =
        parse(
            CASH,
            entry("9.00", "DBIT", DAY)
                .declared("3")
                .detail(detail("3.00", "DBIT").creditor("Not the whole entry")),
            entry("6.00", "DBIT", DAY)
                .declared("3")
                .detail(detail("3.00", "DBIT"))
                .detail(detail("3.00", "DBIT")));

    assertThat(result.rows())
        .extracting(row -> row.canonical().amount(), row -> row.canonical().counterpartyName())
        .containsExactly(
            tuple(new BigDecimal("-9.00"), null), tuple(new BigDecimal("-6.00"), null));
  }

  @Test
  void importsACollectiveEntryWithMoreDetailsThanAreReadWhole() {
    CamtFixtures.Entry entry = entry("501.00", "CRDT", DAY);
    for (int i = 0; i <= CamtXmlReader.MAX_TRANSACTIONS_PER_ENTRY; i++) {
      entry.detail(detail("1.00", "CRDT"));
    }

    assertThat(parse(CASH, entry).rows())
        .singleElement()
        .satisfies(row -> assertThat(row.canonical().amount()).isEqualByComparingTo("501.00"));
  }

  // --- the single detail ---------------------------------------------------------------------

  @Test
  void takesTheOnlyDetailsTextCounterpartyCodeAndOriginalAmount() {
    ImportParseResult result =
        parse(
            CARD,
            entry("1.49", "DBIT", DAY)
                .information("Kartenzahlung")
                .bankCode("PMNT", "CCRD", "POSD")
                .detail(
                    detail("1.49", "DBIT")
                        .creditor("Muster Online Shop")
                        .debtor("Erika Beispiel")
                        .remittance("Order 1")
                        .remittance("Prague")
                        .instructed("37.00", "CZK", "0.0403")
                        .proprietaryCode("CARD-POS")),
            entry("2.00", "CRDT", DAY)
                .information("Fallback text")
                .proprietaryCode("GUTSCHRIFT", "DK")
                .detail(detail("2.00", "CRDT").instructed("2.00", "EUR", "1")));

    CanonicalImportRow purchase = result.rows().get(0).canonical();
    assertThat(purchase.description()).isEqualTo("Order 1 Prague");
    // A debit pays the creditor; the debtor is the account holder.
    assertThat(purchase.counterpartyName()).isEqualTo("Muster Online Shop");
    assertThat(purchase.iso20022BankTransactionCode()).isEqualTo("CARD-POS");
    assertThat(purchase.originalAmount()).isEqualByComparingTo("37.00");
    assertThat(purchase.originalCurrency()).isEqualTo("CZK");
    assertThat(purchase.bankExchangeRate()).isEqualByComparingTo("0.0403");
    assertThat(purchase.transactionType()).isEqualTo("CREDIT_CARD_PURCHASE");

    CanonicalImportRow credit = result.rows().get(1).canonical();
    assertThat(credit.description()).isEqualTo("Fallback text");
    assertThat(credit.counterpartyName()).isNull();
    assertThat(credit.iso20022BankTransactionCode()).isEqualTo("GUTSCHRIFT (DK)");
    // Instructed in the booked currency: nothing original about it.
    assertThat(credit.originalAmount()).isNull();
    assertThat(credit.originalCurrency()).isNull();
    assertThat(credit.bankExchangeRate()).isNull();
    assertThat(result.rows().get(0).rawData())
        .containsEntry(CamtImportReaderService.CELL_INSTRUCTED_AMOUNT, "37.00 CZK")
        .containsEntry(CamtImportReaderService.CELL_CREDITOR, "Muster Online Shop");
  }

  @Test
  void boundsTextsAndKeepsTheEntrysCodeWithoutADetailCode() {
    ImportParseResult result =
        parse(
            CASH,
            entry("1.00", "DBIT", DAY)
                .bankCode("PMNT", "ICDT", "ESCT")
                .detail(
                    detail("1.00", "DBIT").creditor("N".repeat(300)).remittance("T".repeat(600))));

    CanonicalImportRow row = result.rows().get(0).canonical();
    assertThat(row.counterpartyName()).hasSize(140);
    assertThat(row.description()).hasSize(500);
    assertThat(row.iso20022BankTransactionCode()).isEqualTo("PMNT-ICDT-ESCT");
  }

  // --- values --------------------------------------------------------------------------------

  // A moment is the date it is in the deployment's zone; a local date-time the date it names.
  @Test
  void readsDateTimesInTheBusinessZoneAndDatesWithAnOffsetAsWritten() {
    ImportParseResult result =
        parse(
            CASH,
            entry("1.00", "DBIT", DAY).bookedAt("2031-01-31T23:30:00Z"),
            entry("1.00", "DBIT", DAY).bookedAt("2031-01-31T23:30:00.123"),
            entry("1.00", "DBIT", "2031-01-31+01:00"));

    assertThat(result.rows())
        .extracting(row -> row.canonical().bookingDate())
        .containsExactly(
            LocalDate.of(2031, 2, 1), LocalDate.of(2031, 1, 31), LocalDate.of(2031, 1, 31));
  }

  @ParameterizedTest
  @CsvSource({
    "12;50, EUR, DBIT, 2031-01-25, IMPORT_ROW_CAMT_VALUE_INVALID",
    "-5.00, EUR, DBIT, 2031-01-25, IMPORT_ROW_CAMT_VALUE_INVALID",
    "1.00001, EUR, DBIT, 2031-01-25, IMPORT_ROW_AMOUNT_OUT_OF_RANGE",
    "12345678901234567.00, EUR, DBIT, 2031-01-25, IMPORT_ROW_AMOUNT_OUT_OF_RANGE",
    "1.00, EURO, DBIT, 2031-01-25, IMPORT_ROW_CURRENCY_INVALID",
    "1.00, ABC, DBIT, 2031-01-25, IMPORT_ROW_CURRENCY_INVALID",
    "1.00, EUR, BOTH, 2031-01-25, IMPORT_ROW_CAMT_VALUE_INVALID",
    "1.00, EUR, DBIT, 2031-02-30, IMPORT_ROW_DATE_UNPARSEABLE",
    "1.00, EUR, DBIT, 25.01.2031, IMPORT_ROW_DATE_UNPARSEABLE"
  })
  void anUnreadableValueIsAnErrorRowOfItsEntry(
      String amount, String currency, String direction, String date, String code) {
    ParsedImportRow row =
        parse(CASH, entry(amount, direction, date).currency(currency)).rows().get(0);

    assertThat(row.errorCode()).isEqualTo(code);
    assertThat(row.canonical()).isNull();
    assertThat(row.rawData()).containsEntry(CamtImportReaderService.CELL_AMOUNT, amount);
  }

  @Test
  void aMissingDirectionStatusOrReversalOfAnotherValueIsAnErrorRow() {
    ImportParseResult result =
        parse(
            CASH,
            entry("1.00", null, DAY),
            entry("1.00", "DBIT", DAY).status(null),
            entry("1.00", "DBIT", DAY).reversal("maybe"),
            entry("3.00", "DBIT", DAY).declared("two").detail(detail("3.00", "DBIT")));

    assertThat(result.rows())
        .extracting(ParsedImportRow::errorCode, row -> row.errorArgs().get("column"))
        .containsExactly(
            tuple(ImportRowErrorValues.VALUE_MISSING, "Ntry/CdtDbtInd"),
            tuple(ImportRowErrorValues.VALUE_MISSING, "Ntry/Sts"),
            tuple(ImportRowErrorValues.CAMT_VALUE_INVALID, "Ntry/RvslInd"),
            tuple(ImportRowErrorValues.CAMT_VALUE_INVALID, "Ntry/NtryDtls/Btch/NbOfTxs"));
  }

  @Test
  void keepsTheLedgersPrecisionOfAnAmountWithTrailingZeros() {
    assertThat(parse(CASH, entry("1.230000", "CRDT", DAY)).rows().get(0).canonical().amount())
        .isEqualTo(new BigDecimal("1.2300"));
  }

  // --- the account -----------------------------------------------------------------------------

  @Test
  void namesTheAccountByItsIbanAndOnlyAValidOneIsFound() {
    assertThat(parse(CASH, entry("1.00", "CRDT", DAY)).accountIdentifier())
        .satisfies(
            identifier -> {
              assertThat(identifier.kind()).isEqualTo("IBAN");
              assertThat(identifier.value()).isEqualTo(IBAN_NORMALIZED);
            });
    byte[] invalid =
        camt053("001.08")
            .statement("DE00 3704 0044 0532 0130 00", s -> s.entry(entry("1.00", "CRDT", DAY)))
            .bytes();
    assertThat(service.parse(invalid, CASH).accountIdentifier().found()).isFalse();
    byte[] other =
        camt053("001.08")
            .statementOfOtherAccount("0532013000", s -> s.entry(entry("1.00", "CRDT", DAY)))
            .bytes();
    assertThat(service.parse(other, CASH).accountIdentifier().found()).isFalse();
  }

  @Test
  void refusesAFileOfSeveralAccountsButReadsSeveralStatementsOfOne() {
    byte[] two =
        camt053("001.08")
            .statement(IBAN, s -> s.entry(entry("1.00", "CRDT", DAY)))
            .statement(OTHER_IBAN, s -> s.entry(entry("2.00", "CRDT", DAY)))
            .bytes();
    assertThatThrownBy(() -> service.parse(two, CASH))
        .isInstanceOfSatisfying(
            ImportFileRejectedException.class,
            e -> assertThat(code(e)).isEqualTo(ApiErrorCode.IMPORT_CAMT_MULTIPLE_ACCOUNTS));
    byte[] acrossArchive =
        zip(
            camt053("001.08").statement(IBAN, s -> s.entry(entry("1.00", "CRDT", DAY))).bytes(),
            camt052("001.08")
                .statement(OTHER_IBAN, s -> s.entry(entry("1.00", "CRDT", DAY)))
                .bytes());
    assertThatThrownBy(() -> service.parse(acrossArchive, CASH))
        .isInstanceOfSatisfying(
            ImportFileRejectedException.class,
            e -> assertThat(code(e)).isEqualTo(ApiErrorCode.IMPORT_CAMT_MULTIPLE_ACCOUNTS));

    byte[] oneAccount =
        zip(
            camt053("001.08").statement(IBAN, s -> s.entry(entry("1.00", "CRDT", DAY))).bytes(),
            camt053("001.04")
                .statement(IBAN.replace(" ", ""), s -> s.entry(entry("2.00", "CRDT", "2031-01-26")))
                .bytes());
    assertThat(service.parse(oneAccount, CASH).rows()).hasSize(2);
  }

  // --- balances --------------------------------------------------------------------------------

  @Test
  void aStatementStatesItsClosingBookedBalancesOnly() {
    byte[] statement =
        camt053("001.08")
            .statement(
                IBAN,
                s ->
                    s.balance("OPBD", "30.00", "CRDT", "2031-01-01")
                        .balance("ITBD", "40.00", "CRDT", "2031-01-15")
                        .balance("CLBD", "12.50", "DBIT", "2031-01-31")
                        .balanceAt("CLBD", "20.00", "CRDT", "2031-02-28T23:30:00Z")
                        .entry(entry("1.00", "CRDT", DAY)))
            .bytes();

    ImportStatementBalances balances = service.parse(statement, CASH).statementBalances();

    assertThat(balances.balances())
        .containsExactly(
            new ImportStatementBalances.Stated(
                LocalDate.of(2031, 1, 31), new BigDecimal("-12.50"), "EUR"),
            new ImportStatementBalances.Stated(
                LocalDate.of(2031, 3, 1), new BigDecimal("20.00"), "EUR"));
    assertThat(balances.unreadable()).isZero();
    assertThat(balances.fileOrdered()).isFalse();
  }

  // An unreadable closing balance hides the statement balance in its currency, never another one.
  @Test
  void anUnreadableClosingBalanceIsCountedInItsCurrency() {
    byte[] statement =
        camt053("001.08")
            .statement(
                IBAN,
                s ->
                    s.balance("CLBD", "1,00", "EUR", "CRDT", "<Dt>2031-01-31</Dt>")
                        .balance("CLBD", "5.00", "XXXX", "CRDT", "<Dt>2031-01-31</Dt>")
                        .entry(entry("1.00", "CRDT", DAY)))
            .bytes();

    ImportStatementBalances balances = service.parse(statement, CASH).statementBalances();

    assertThat(balances.balances()).isEmpty();
    assertThat(balances.unreadableCurrencies()).containsExactly("EUR", null);
  }

  @Test
  void anIntradayReportStatesNoStatementBalance() {
    byte[] report =
        camt052("001.08")
            .statement(
                IBAN,
                s ->
                    s.balance("CLBD", "12.50", "CRDT", "2031-01-31")
                        .entry(entry("1.00", "CRDT", DAY)))
            .bytes();

    assertThat(service.parse(report, CASH).statementBalances()).isNull();
    assertThat(
            service
                .parse(
                    camt053("001.08")
                        .statement(IBAN, s -> s.entry(entry("1.00", "CRDT", DAY)))
                        .bytes(),
                    CASH)
                .statementBalances()
                .balances())
        .isEmpty();
  }

  // --- the file --------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.054.001.08\"/>|IMPORT_CAMT_UNSUPPORTED",
        "<Document|IMPORT_FILE_MALFORMED",
        "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/hosts\">]><d>&x;</d>"
            + "|IMPORT_FILE_MALFORMED",
        "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\"><BkToCstmrStmt>"
            + "<Stmt/></BkToCstmrStmt></Document>|IMPORT_FILE_NO_DATA_ROWS"
      })
  void refusesAFileItCannotImportWithItsCode(String fileAndCode) {
    String[] parts = fileAndCode.split("\\|");
    byte[] file = parts[0].getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> service.parse(file, CASH))
        .isInstanceOfSatisfying(
            ImportFileRejectedException.class, e -> assertThat(code(e)).isEqualTo(parts[1]));
  }

  @Test
  void refusesAFileBeyondItsLimits() {
    byte[] deep =
        ("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\"><BkToCstmrStmt>"
                + "<a>".repeat(50)
                + "</a>".repeat(50)
                + "</BkToCstmrStmt></Document>")
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> service.parse(deep, CASH))
        .isInstanceOfSatisfying(
            ImportFileRejectedException.class,
            e -> assertThat(code(e)).isEqualTo(ApiErrorCode.IMPORT_CAMT_LIMIT_EXCEEDED));
  }

  // Splitting may make more rows than entries: the import's row limit holds all the same.
  @Test
  void refusesMoreRowsThanOneImportMayHold() {
    StringBuilder entries = new StringBuilder();
    String twoDetails =
        "<Ntry><Amt Ccy=\"EUR\">2</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>BOOK</Sts>"
            + "<BookgDt><Dt>2031-01-25</Dt></BookgDt><NtryDtls>"
            + "<TxDtls><Amt Ccy=\"EUR\">1</Amt></TxDtls><TxDtls><Amt Ccy=\"EUR\">1</Amt></TxDtls>"
            + "</NtryDtls></Ntry>";
    for (int i = 0; i <= ImportFileParserService.MAX_DATA_ROWS / 2; i++) {
      entries.append(twoDetails);
    }
    byte[] file =
        ("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.04\"><BkToCstmrStmt><Stmt>"
                + entries
                + "</Stmt></BkToCstmrStmt></Document>")
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> service.parse(file, CASH))
        .isInstanceOfSatisfying(
            ImportFileRejectedException.class,
            e -> assertThat(code(e)).isEqualTo(ApiErrorCode.IMPORT_FILE_TOO_MANY_ROWS));
  }

  @Test
  void tellsCamtFilesByTheirBytes() {
    assertThat(service.isCamtFile("<Document/>".getBytes(StandardCharsets.UTF_8))).isTrue();
    assertThat(service.isCamtFile("Datum;Betrag".getBytes(StandardCharsets.UTF_8))).isFalse();
  }

  // --- helpers ---------------------------------------------------------------------------------

  private ImportParseResult parse(ImportAccountContext account, CamtFixtures.Entry... entries) {
    return service.parse(statement(s -> s, entries), account);
  }

  private List<String> types(ImportAccountContext account, CamtFixtures.Entry... entries) {
    return parse(account, entries).rows().stream()
        .map(row -> row.canonical().transactionType())
        .toList();
  }

  private static byte[] statement(
      UnaryOperator<CamtFixtures.Statement> extra, CamtFixtures.Entry... entries) {
    return camt053("001.08")
        .statement(
            IBAN,
            s -> {
              extra.apply(s);
              for (CamtFixtures.Entry entry : entries) {
                s.entry(entry);
              }
            })
        .bytes();
  }

  private static String code(ImportFileRejectedException e) {
    return e.getCode();
  }
}
