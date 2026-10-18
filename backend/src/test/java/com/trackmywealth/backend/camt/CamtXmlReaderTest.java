package com.trackmywealth.backend.camt;

import static com.trackmywealth.backend.testsupport.CamtFixtures.IBAN;
import static com.trackmywealth.backend.testsupport.CamtFixtures.camt052;
import static com.trackmywealth.backend.testsupport.CamtFixtures.camt053;
import static com.trackmywealth.backend.testsupport.CamtFixtures.detail;
import static com.trackmywealth.backend.testsupport.CamtFixtures.entry;
import static com.trackmywealth.backend.testsupport.CamtFixtures.namespace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.testsupport.CamtFixtures;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** #350: the structure of CAMT XML, read within its limits. Every statement is synthetic. */
class CamtXmlReaderTest {

  private static final String DOCUMENT_START =
      "<?xml version=\"1.0\"?><Document xmlns=\"" + namespace("camt.053", "001.08") + "\">";

  static Stream<Arguments> versions() {
    return Stream.of("camt.053", "camt.052")
        .flatMap(
            message -> Stream.of("001.02", "001.04", "001.08").map(v -> Arguments.of(message, v)));
  }

  // Each supported message and version: its status, detail amount and party name forms.
  @ParameterizedTest
  @MethodSource("versions")
  void readsEverySupportedMessageAndVersion(String messageType, String version) throws Exception {
    CamtFixtures.Message message =
        ("camt.053".equals(messageType) ? camt053(version) : camt052(version))
            .statement(
                IBAN,
                statement ->
                    statement
                        .balance("CLBD", "100.00", "CRDT", "2031-01-31")
                        .entry(
                            entry("12.34", "DBIT", "2031-01-25")
                                .valueDate("2031-01-26")
                                .reference("BANK-REF-1")
                                .information("Invented booking")
                                .bankCode("PMNT", "ICDT", "ESCT")
                                .detail(
                                    detail("12.34", "DBIT")
                                        .reference("TX-REF-1")
                                        .endToEndId("E2E-1")
                                        .creditor("Muster Markt GmbH")
                                        .debtor("Erika Beispiel")
                                        .remittance("Invoice 4711")
                                        .instructed("13.00", "USD", "0.9492"))));

    CamtMessage read = CamtFileReader.read(message.bytes()).get(0);

    assertThat(read.messageType()).isEqualTo(messageType);
    assertThat(read.version()).isEqualTo(version);
    CamtStatement statement = read.statements().get(0);
    assertThat(statement.iban()).isEqualTo(IBAN);
    assertThat(statement.balances())
        .containsExactly(
            new CamtBalance(
                "CLBD", new CamtAmount("100.00", "EUR"), "CRDT", new CamtDate("2031-01-31", null)));
    CamtEntry entry = statement.entries().get(0);
    assertThat(entry.status()).isEqualTo("BOOK");
    assertThat(entry.amount()).isEqualTo(new CamtAmount("12.34", "EUR"));
    assertThat(entry.creditDebit()).isEqualTo("DBIT");
    assertThat(entry.bookingDate()).isEqualTo(new CamtDate("2031-01-25", null));
    assertThat(entry.valueDate()).isEqualTo(new CamtDate("2031-01-26", null));
    assertThat(entry.accountServicerReference()).isEqualTo("BANK-REF-1");
    assertThat(entry.additionalInfo()).isEqualTo("Invented booking");
    assertThat(entry.bankCode()).isEqualTo(new CamtBankCode("PMNT", "ICDT", "ESCT", null, null));
    assertThat(entry.transactionsOmitted()).isFalse();
    CamtTransaction transaction = entry.transactions().get(0);
    assertThat(transaction.amount()).isEqualTo(new CamtAmount("12.34", "EUR"));
    // A detail has a direction of its own only from .001.04.
    assertThat(transaction.creditDebit()).isEqualTo("001.02".equals(version) ? null : "DBIT");
    assertThat(transaction.instructedAmount()).isEqualTo(new CamtAmount("13.00", "USD"));
    assertThat(transaction.exchangeRate()).isEqualTo("0.9492");
    assertThat(transaction.accountServicerReference()).isEqualTo("TX-REF-1");
    assertThat(transaction.endToEndId()).isEqualTo("E2E-1");
    assertThat(transaction.creditorName()).isEqualTo("Muster Markt GmbH");
    assertThat(transaction.debtorName()).isEqualTo("Erika Beispiel");
    assertThat(transaction.remittance()).containsExactly("Invoice 4711");
  }

  @Test
  void readsTheDetailsOfEveryEntryDetailsBlockAndTheDeclaredCount() throws Exception {
    String entryXml =
        "<Ntry><Amt Ccy=\"EUR\">7.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>"
            + "<BookgDt><DtTm>2031-02-01T10:15:00+01:00</DtTm></BookgDt>"
            + "<NtryDtls><Btch><NbOfTxs>2</NbOfTxs></Btch>"
            + "<TxDtls><Amt Ccy=\"EUR\">3.00</Amt></TxDtls></NtryDtls>"
            + "<NtryDtls><TxDtls><Amt Ccy=\"EUR\">4.00</Amt></TxDtls></NtryDtls></Ntry>";

    CamtEntry entry = only(DOCUMENT_START + statementOf(entryXml) + "</Document>");

    assertThat(entry.declaredTransactions()).isEqualTo("2");
    assertThat(entry.transactions())
        .extracting(CamtTransaction::amount)
        .containsExactly(new CamtAmount("3.00", "EUR"), new CamtAmount("4.00", "EUR"));
    assertThat(entry.bookingDate()).isEqualTo(new CamtDate(null, "2031-02-01T10:15:00+01:00"));
  }

  @Test
  void keepsAtMostTheTransactionLimitAndMarksTheEntry() throws Exception {
    String details =
        "<TxDtls><Amt Ccy=\"EUR\">1.00</Amt></TxDtls>"
            .repeat(CamtXmlReader.MAX_TRANSACTIONS_PER_ENTRY + 1);
    String entryXml =
        "<Ntry><Amt Ccy=\"EUR\">501.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>BOOK</Sts>"
            + "<NtryDtls>"
            + details
            + "</NtryDtls></Ntry>";

    CamtEntry entry = only(DOCUMENT_START + statementOf(entryXml) + "</Document>");

    assertThat(entry.transactions()).hasSize(CamtXmlReader.MAX_TRANSACTIONS_PER_ENTRY);
    assertThat(entry.transactionsOmitted()).isTrue();
  }

  // Supplementary data of another namespace may name its elements like CAMT's: never read.
  @Test
  void readsPastElementsOfAnotherNamespace() throws Exception {
    String entryXml =
        "<Ntry><Amt Ccy=\"EUR\">1.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>BOOK</Sts>"
            + "<SplmtryData><Envlp><x:AcctSvcrRef xmlns:x=\"urn:example:other\">FAKE</x:AcctSvcrRef>"
            + "</Envlp></SplmtryData></Ntry>"
            + "<x:Ntry xmlns:x=\"urn:example:other\"><x:Amt Ccy=\"EUR\">9.00</x:Amt></x:Ntry>";

    CamtMessage message =
        CamtFileReader.read(bytes(DOCUMENT_START + statementOf(entryXml) + "</Document>")).get(0);

    assertThat(message.statements().get(0).entries())
        .singleElement()
        .satisfies(entry -> assertThat(entry.accountServicerReference()).isNull());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "urn:iso:std:iso:20022:tech:xsd:camt.054.001.08",
        "urn:iso:std:iso:20022:tech:xsd:camt.053.001.03",
        "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09",
        "urn:example:statement"
      })
  void refusesAnyOtherMessageOrVersion(String otherNamespace) {
    byte[] xml =
        bytes(
            "<Document xmlns=\""
                + otherNamespace
                + "\"><BkToCstmrStmt><Stmt/></BkToCstmrStmt></Document>");

    assertThatThrownBy(() -> CamtFileReader.read(xml)).isInstanceOf(CamtUnsupportedException.class);
  }

  @Test
  void refusesARootOtherThanDocumentAndAMessageElementOfAnotherMessage() {
    assertThatThrownBy(
            () ->
                CamtFileReader.read(
                    bytes("<Statement xmlns=\"" + namespace("camt.053", "001.08") + "\"/>")))
        .isInstanceOf(CamtUnsupportedException.class);
    assertThatThrownBy(
            () -> CamtFileReader.read(bytes(DOCUMENT_START + "<BkToCstmrAcctRpt/></Document>")))
        .isInstanceOf(CamtUnsupportedException.class);
  }

  static Stream<String> hostileDocuments() {
    String ns = namespace("camt.053", "001.08");
    return Stream.of(
        // XXE: a local file.
        "<?xml version=\"1.0\"?><!DOCTYPE Document [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<Document xmlns=\""
            + ns
            + "\"><BkToCstmrStmt><Stmt><Acct><Id><IBAN>&xxe;"
            + "</IBAN></Id></Acct></Stmt></BkToCstmrStmt></Document>",
        // XXE: a network resource, and an external DTD.
        "<?xml version=\"1.0\"?><!DOCTYPE Document SYSTEM \"http://127.0.0.1:9/camt.dtd\">"
            + "<Document xmlns=\""
            + ns
            + "\"/>",
        // Entity expansion ("billion laughs").
        "<?xml version=\"1.0\"?><!DOCTYPE Document [<!ENTITY a \"aaaaaaaaaa\">"
            + "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\"><!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;\">]>"
            + "<Document xmlns=\""
            + ns
            + "\"><BkToCstmrStmt><GrpHdr><MsgId>&c;</MsgId>"
            + "</GrpHdr></BkToCstmrStmt></Document>",
        // A parameter entity.
        "<?xml version=\"1.0\"?><!DOCTYPE Document [<!ENTITY % p SYSTEM \"http://127.0.0.1:9/p\">"
            + "%p;]><Document xmlns=\""
            + ns
            + "\"/>",
        // An undeclared entity without a DTD.
        "<Document xmlns=\""
            + ns
            + "\"><BkToCstmrStmt><GrpHdr><MsgId>&undeclared;</MsgId>"
            + "</GrpHdr></BkToCstmrStmt></Document>");
  }

  @ParameterizedTest
  @MethodSource("hostileDocuments")
  void refusesDocumentTypeDeclarationsAndEntities(String hostile) {
    assertThatThrownBy(() -> CamtFileReader.read(bytes(hostile)))
        .isInstanceOf(CamtFormatException.class)
        .isNotInstanceOf(CamtUnsupportedException.class)
        // The message is the reader's own: nothing of the document is repeated.
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("passwd", "127.0.0.1"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<Document",
        "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\"><BkToCstmrStmt>",
        "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\"></Other>"
      })
  void refusesMalformedXml(String malformed) {
    assertThatThrownBy(() -> CamtFileReader.read(bytes(malformed)))
        .isInstanceOf(CamtFormatException.class)
        .hasMessage("The XML is malformed.");
  }

  @Test
  void refusesNestingDeeperThanTheLimit() {
    String deep = "<a>".repeat(CamtXmlReader.MAX_DEPTH) + "</a>".repeat(CamtXmlReader.MAX_DEPTH);

    // Within the message element: anything else at its level is no CAMT message.
    String document = DOCUMENT_START + "<BkToCstmrStmt>" + deep + "</BkToCstmrStmt></Document>";

    assertThatThrownBy(() -> CamtFileReader.read(bytes(document)))
        .isInstanceOf(CamtLimitException.class)
        .hasMessageContaining("deeper");
  }

  @Test
  void refusesAnOverlongTextAndAnOverlongCurrencyAttribute() {
    String text = "x".repeat(CamtXmlReader.MAX_TEXT_LENGTH + 1);
    assertThatThrownBy(
            () ->
                CamtFileReader.read(
                    bytes(
                        DOCUMENT_START
                            + "<BkToCstmrStmt><GrpHdr><MsgId>"
                            + text
                            + "</MsgId></GrpHdr></BkToCstmrStmt></Document>")))
        .isInstanceOf(CamtLimitException.class);
    String entryXml = "<Ntry><Amt Ccy=\"" + text + "\">1.00</Amt></Ntry>";
    assertThatThrownBy(
            () ->
                CamtFileReader.read(bytes(DOCUMENT_START + statementOf(entryXml) + "</Document>")))
        .isInstanceOf(CamtLimitException.class);
  }

  @Test
  void refusesAnEntryOfMoreElementsThanTheLimit() {
    String entryXml =
        "<Ntry><AddtlInfInd>"
            + "<x/>".repeat(CamtXmlReader.MAX_ENTRY_ELEMENTS)
            + "</AddtlInfInd></Ntry>";

    assertThatThrownBy(
            () ->
                CamtFileReader.read(bytes(DOCUMENT_START + statementOf(entryXml) + "</Document>")))
        .isInstanceOf(CamtLimitException.class)
        .hasMessageContaining(Integer.toString(CamtXmlReader.MAX_ENTRY_ELEMENTS));
  }

  @Test
  void refusesMoreEntriesOrElementsThanOneImportMayHold() {
    String entries = "<Ntry/>".repeat(CamtReadBudget.MAX_ENTRIES + 1);
    assertThatThrownBy(
            () -> CamtFileReader.read(bytes(DOCUMENT_START + statementOf(entries) + "</Document>")))
        .isInstanceOf(CamtLimitException.class)
        .hasMessageContaining("entries");

    String elements = "<x/>".repeat(CamtReadBudget.MAX_ELEMENTS + 1);
    assertThatThrownBy(
            () ->
                CamtFileReader.read(
                    bytes(
                        DOCUMENT_START
                            + "<BkToCstmrStmt>"
                            + elements
                            + "</BkToCstmrStmt></Document>")))
        .isInstanceOf(CamtLimitException.class)
        .hasMessageContaining("elements");
  }

  private static CamtEntry only(String xml) throws Exception {
    return CamtFileReader.read(bytes(xml)).get(0).statements().get(0).entries().get(0);
  }

  private static String statementOf(String content) {
    return "<BkToCstmrStmt><Stmt><Acct><Id><IBAN>"
        + IBAN
        + "</IBAN></Id></Acct>"
        + content
        + "</Stmt></BkToCstmrStmt>";
  }

  private static byte[] bytes(String xml) {
    return xml.getBytes(StandardCharsets.UTF_8);
  }
}
