package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.ImportAccountContext;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** #350: invented ISO 20022 data only; no real bank files or identifiers. */
class Camt053ImportReaderTest {

  private static final String NS =
      "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08";
  private final Camt053ImportReader reader = new Camt053ImportReader();

  private static byte[] xml(String body) {
    return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Document xmlns=\"" + NS + "\"><BkToCstmrStmt><Stmt>"
            + "<Acct><Id><IBAN>DE89 3704 0044 0532 0130 00</IBAN></Id></Acct>"
            + body + "</Stmt></BkToCstmrStmt></Document>")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String entry(String status, String amount, String direction, String ref) {
    return "<Ntry><Sts><Cd>" + status + "</Cd></Sts>"
        + "<Amt Ccy=\"EUR\">" + amount + "</Amt>"
        + "<CdtDbtInd>" + direction + "</CdtDbtInd>"
        + "<BookgDt><Dt>2031-01-25</Dt></BookgDt>"
        + "<ValDt><Dt>2031-01-26</Dt></ValDt>"
        + (ref == null ? "" : "<AcctSvcrRef>" + ref + "</AcctSvcrRef>")
        + "<AddtlNtryInf>Invented booking</AddtlNtryInf></Ntry>";
  }

  @Test
  void recognizesXmlWithoutMistakingCsvForXml() {
    assertThat(Camt053ImportReader.looksLikeXml(
        "statement.xml", "application/octet-stream", xml(entry("BOOK", "1.00", "CRDT", "r"))))
        .isTrue();
    assertThat(Camt053ImportReader.looksLikeXml("statement.csv", "text/csv",
        "Date;Amount\n".getBytes(StandardCharsets.UTF_8))).isFalse();
  }

  @Test
  void readsBookedRowsAndClosingBalanceButSkipsPendingEntries() {
    byte[] statement = xml(entry("BOOK", "12.34", "DBIT", "booking-1")
        + entry("PDNG", "3.00", "CRDT", "pending")
        + entry("BOOK", "4.00", "CRDT", null)
        + "<Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp>"
        + "<Amt Ccy=\"EUR\">100.00</Amt><CdtDbtInd>CRDT</CdtDbtInd>"
        + "<Dt><Dt>2031-01-31</Dt></Dt></Bal>"
        + "<Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp>"
        + "<Amt Ccy=\"EUR\">30.00</Amt><CdtDbtInd>CRDT</CdtDbtInd>"
        + "<Dt><Dt>2031-01-01</Dt></Dt></Bal>");
    var result = reader.parse(statement, new ImportAccountContext("EUR", false, false));

    assertThat(result.rows()).hasSize(3);
    assertThat(result.rows().get(0).canonical().amount()).isEqualByComparingTo("-12.34");
    assertThat(result.rows().get(0).canonical().transactionType()).isEqualTo("EXPENSE");
    assertThat(result.rows().get(0).canonical().externalId()).isEqualTo("booking-1");
    assertThat(result.rows().get(1).isSkipped()).isTrue();
    assertThat(result.rows().get(2).canonical().amount()).isEqualByComparingTo("4.00");
    assertThat(result.rows().get(2).canonical().externalId()).isNull();
    assertThat(result.accountIdentifier().value()).isEqualTo("DE89370400440532013000");
    assertThat(result.statementBalances().balances()).hasSize(1);
    assertThat(result.statementBalances().balances().get(0).amount())
        .isEqualByComparingTo(new BigDecimal("100"));
  }

  @Test
  void refusesAmbiguousSplitEntriesWithoutCreatingExtraMoney() {
    String details = "<NtryDtls>"
        + "<TxDtls><AmtDtls><TxAmt><Amt Ccy=\"EUR\">3.00</Amt></TxAmt></AmtDtls>"
        + "<CdtDbtInd>CRDT</CdtDbtInd></TxDtls>"
        + "<TxDtls><AmtDtls><TxAmt><Amt Ccy=\"EUR\">4.00</Amt></TxAmt></AmtDtls>"
        + "<CdtDbtInd>CRDT</CdtDbtInd></TxDtls></NtryDtls>";
    byte[] input = xml("<Ntry><Sts><Cd>BOOK</Cd></Sts><Amt Ccy=\"EUR\">8.00</Amt>"
        + "<CdtDbtInd>CRDT</CdtDbtInd><BookgDt><Dt>2031-02-01</Dt></BookgDt>"
        + details + "</Ntry>");
    var result = reader.parse(input, new ImportAccountContext("EUR", false, false));

    assertThat(result.rows()).hasSize(1);
    assertThat(result.rows().get(0).errorCode())
        .isEqualTo(ImportRowErrorValues.CAMT_ENTRY_AMBIGUOUS);
  }

  @Test
  void neverResolvesExternalEntitiesOrAcceptsAnotherDocumentNamespace() {
    byte[] hostile = ("<?xml version=\"1.0\"?><!DOCTYPE Document ["
        + "<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
        + "<Document xmlns=\"" + NS + "\"><BkToCstmrStmt>"
        + "<Stmt><Acct><Id><IBAN>&xxe;</IBAN></Id></Acct></Stmt>"
        + "</BkToCstmrStmt></Document>").getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> reader.parse(hostile, new ImportAccountContext("EUR", false, false)))
        .isInstanceOf(ImportFileRejectedException.class);
    assertThatThrownBy(() -> reader.parse(
        "<?xml version=\"1.0\"?><Document xmlns=\"urn:invalid\"/>"
            .getBytes(StandardCharsets.UTF_8), new ImportAccountContext("EUR", false, false)))
        .isInstanceOf(ImportFileRejectedException.class);
  }

  @Test
  void rejectsMalformedOrExcessivelyLargeDocuments() {
    assertThatThrownBy(() -> reader.parse("<broken".getBytes(StandardCharsets.UTF_8),
        new ImportAccountContext("EUR", false, false)))
        .isInstanceOf(ImportFileRejectedException.class);
    assertThatThrownBy(() -> reader.parse(new byte[5 * 1024 * 1024 + 1],
        new ImportAccountContext("EUR", false, false)))
        .isInstanceOf(ImportFileRejectedException.class);
  }
}
