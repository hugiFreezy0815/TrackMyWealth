package com.trackmywealth.backend.service;

import static com.trackmywealth.backend.service.SyntheticStatements.at;
import static com.trackmywealth.backend.service.SyntheticStatements.atFoot;
import static com.trackmywealth.backend.service.SyntheticStatements.endingAt;
import static com.trackmywealth.backend.service.SyntheticStatements.strayBlank;

import com.trackmywealth.backend.service.SyntheticStatements.Cell;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * US-07-15 (#275): synthetic PDF statements in the layouts of the shipped PDF templates (V77-V79,
 * V93, docs/architecture/import-source-analysis.md 6.8), named by each template's
 * fixture_reference. They reproduce the structure of the real statements - markers, header labels,
 * balance lines, columns, page furniture - with invented values only. A real statement is never
 * used.
 */
public final class ShippedTemplateFixtures {

  // Where the LBS header labels start, and where an amount under Belastungen or Gutschriften ends.
  private static final float LBS_BOOKED = 40;
  private static final float LBS_VALUE = 95;
  private static final float LBS_TEXT = 150;
  private static final float LBS_DEBIT_END = 345;
  private static final float LBS_CREDIT_END = 415;
  private static final float LBS_PANEL = 450;
  private static final float LOAN_DEBIT_END = 400;
  private static final float LOAN_CREDIT_END = 500;
  // Where a card statement's amount ends, and where the sign after it stands.
  private static final float CARD_AMOUNT_END = 490;
  private static final float CARD_SIGN = 495;

  private ShippedTemplateFixtures() {}

  /**
   * A Yuh account statement (YUH-1): a summary, then a CHF section (three bookings, a continuation
   * line, a closing "Schlussbilanz" that includes the savings pocket and so differs from the
   * running balance) and a EUR section (one booking). As in the real statement, each section states
   * its opening and closing "Saldo per" before the table, and the balance label carries the
   * currency.
   */
  public static byte[] yuhStatement() throws IOException {
    List<List<Cell>> page = new ArrayList<>();
    page.add(List.of(at(40, "Dein Kontoauszug")));
    page.add(List.of(at(40, "Kontoauszug vom 01.01.2031 bis 31.01.2031")));
    page.add(List.of(at(40, "Kunde X-00000"), at(300, "Endsaldo 1'308.55 CHF")));
    page.add(List.of(at(40, "IBAN : CH21 0076 2000 0000 0000 0")));
    yuhSection(page, "CHF", "263.40", "1'248.55");
    page.add(List.of(at(40, "01.01.2031"), at(100, "Anfangsbestand"), endingAt(530, "263.40")));
    page.add(
        SyntheticStatements.booking(
            0, "03.01.2031", "Zahlung von", "R-0000001", null, "1'000.00", "1'263.40"));
    page.add(List.of(at(100, "Erika Beispiel")));
    page.add(
        SyntheticStatements.booking(
            0, "05.01.2031", "Spareinlage", "R-0000002", "17.35", null, "1'246.05"));
    page.add(
        SyntheticStatements.booking(
            0, "07.01.2031", "Dividende", "R-0000003", null, "2.50", "1'248.55"));
    page.add(List.of(at(40, "31.01.2031"), at(100, "Schlussbilanz"), endingAt(530, "1'265.90")));
    yuhSection(page, "EUR", "103.27", "62.08");
    page.add(List.of(at(40, "01.01.2031"), at(100, "Anfangsbestand"), endingAt(530, "103.27")));
    page.add(
        SyntheticStatements.booking(
            0, "10.01.2031", "Zahlung an", "R-0000004", "41.19", null, "62.08"));
    page.add(List.of(at(100, "Max Muster")));
    page.add(List.of(atFoot(40, "Invented Bank AG, Beispielweg 1"), at(480, "Seite 1 / 1")));
    return SyntheticStatements.statement(List.of(page));
  }

  private static void yuhSection(
      List<List<Cell>> page, String currency, String opening, String closing) {
    page.add(List.of(at(40, "Kontoauszug in " + currency)));
    page.add(List.of(at(40, "Saldo per 01.01.2031"), endingAt(560, opening + " " + currency)));
    page.add(List.of(at(40, "Saldo per 31.01.2031"), endingAt(560, closing + " " + currency)));
    List<Cell> header = SyntheticStatements.header(0);
    header.add(at(535, "(" + currency + ")"));
    page.add(header);
  }

  /**
   * A Sparkasse annual loan statement (SPK-6): signed amounts in the Soll or Haben column, one with
   * a "/ Wert:" value date, opening and closing "Kontostand am", and one booking whose date the
   * text layer splits after its first digit, as a real statement's kerning does.
   */
  public static byte[] sparkasseLoanStatement() throws IOException {
    List<List<Cell>> page = new ArrayList<>();
    page.add(List.of(at(40, "Sparkasse Musterstadt")));
    page.add(
        List.of(
            at(40, "Jahreskontoauszug f\u00fcr den Zeitraum 01.01.2031 - 31.12.2031"),
            at(480, "Seite 1 von 1")));
    page.add(
        List.of(
            at(40, "Darlehen 0000000000, DE89 3704 0044 0532 0130 00"),
            at(330, "Tilgungsform: Annuit\u00e4tendarlehen")));
    page.add(
        List.of(
            at(40, "Datum"),
            at(100, "Erl\u00e4uterung"),
            at(330, "Betrag Soll EUR"),
            at(430, "Betrag Haben EUR")));
    page.add(List.of(at(45, "Kontostand am 01.01.2031"), endingAt(LOAN_DEBIT_END, "-50.000,00")));
    page.add(loanBooking("29.01.2031", "Darlehenszins", "-123,45", LOAN_DEBIT_END));
    page.add(loanBooking("29.01.2031", "Darlehensleistung", "456,78", LOAN_CREDIT_END));
    // #334: interest settled on the 30/360 day count carries a value date February does not have.
    page.add(
        loanBooking("28.02.2031", "Darlehenszins / Wert: 30.02.2031", "-118,63", LOAN_DEBIT_END));
    page.add(loanBooking("28.02.2031", "Kontoauszugspreis", "-2,50", LOAN_DEBIT_END));
    // #322: two invisible spaces over the date's first digit, which the text layer once read as
    // "3 1.03.2031".
    page.add(
        List.of(
            at(40, "31.03.2031"),
            strayBlank(39.6f),
            strayBlank(41.55f),
            at(100, "Darlehensleistung"),
            endingAt(LOAN_CREDIT_END, "456,78")));
    page.add(List.of(at(45, "Kontostand am 31.12.2031"), endingAt(LOAN_DEBIT_END, "-49.331,02")));
    page.add(
        List.of(at(40, "Nachweis f\u00fcr Darlehen 0000000000 Zeitraum 01.01.2031 - 31.12.2031")));
    page.add(List.of(atFoot(40, "Sparkasse Musterstadt, Anstalt des \u00f6ffentlichen Rechts")));
    return SyntheticStatements.statement(List.of(page));
  }

  /**
   * US-07-21: a Sparkasse annual loan statement for 2019, outside every real sample's years, whose
   * bookings add up - opening -50.000,00, interest and an instalment in January, interest with a
   * value date and the statement fee in February, closing -49.787,80 - and no split date.
   */
  public static byte[] sparkasseLoanStatement2019() throws IOException {
    List<List<Cell>> page = new ArrayList<>();
    page.add(List.of(at(40, "Sparkasse Musterstadt")));
    page.add(
        List.of(
            at(40, "Jahreskontoauszug f\u00fcr den Zeitraum 01.01.2019 - 31.12.2019"),
            at(480, "Seite 1 von 1")));
    page.add(
        List.of(
            at(40, "Darlehen 0000000000, DE00 0000 0000 0000 0000 00"),
            at(330, "Tilgungsform: Annuit\u00e4tendarlehen")));
    page.add(
        List.of(
            at(40, "Datum"),
            at(100, "Erl\u00e4uterung"),
            at(330, "Betrag Soll EUR"),
            at(430, "Betrag Haben EUR")));
    page.add(List.of(at(45, "Kontostand am 01.01.2019"), endingAt(LOAN_DEBIT_END, "-50.000,00")));
    page.add(loanBooking("29.01.2019", "Darlehenszins", "-123,45", LOAN_DEBIT_END));
    page.add(loanBooking("29.01.2019", "Darlehensleistung", "456,78", LOAN_CREDIT_END));
    page.add(
        loanBooking("28.02.2019", "Darlehenszins / Wert: 01.03.2019", "-118,63", LOAN_DEBIT_END));
    page.add(loanBooking("28.02.2019", "Kontoauszugspreis", "-2,50", LOAN_DEBIT_END));
    page.add(List.of(at(45, "Kontostand am 31.12.2019"), endingAt(LOAN_DEBIT_END, "-49.787,80")));
    page.add(
        List.of(at(40, "Nachweis f\u00fcr Darlehen 0000000000 Zeitraum 01.01.2019 - 31.12.2019")));
    page.add(List.of(atFoot(40, "Sparkasse Musterstadt, Anstalt des \u00f6ffentlichen Rechts")));
    return SyntheticStatements.statement(List.of(page));
  }

  private static List<Cell> loanBooking(String date, String text, String amount, float end) {
    return List.of(at(40, date), at(100, text), endingAt(end, amount));
  }

  /**
   * US-07-25 (#339): a Sparkasse credit-card statement (Mastercard) for February 2031: the period
   * and masked card number on the title line, purchase and booking date on each booking line, the
   * sign after the amount ("-" a purchase, "+" the payment received), the previous balance on a
   * dated line and the new balance on one without a date. The bookings add up: -1.204,37 - 23,61 -
   * 147,93 + 1.204,37 - 8,47 = -180,01.
   */
  public static byte[] sparkasseCardStatement() throws IOException {
    return sparkasseCardStatement("2031");
  }

  /**
   * The same statement for February 2019, outside every real sample's years and in the past, so its
   * new balance is recorded as a snapshot and reconciled (a future one is out of range).
   */
  public static byte[] sparkasseCardStatement2019() throws IOException {
    return sparkasseCardStatement("2019");
  }

  private static byte[] sparkasseCardStatement(String year) throws IOException {
    String yy = "." + year.substring(2);
    List<List<Cell>> page = new ArrayList<>();
    page.add(List.of(at(40, "Sparkasse Musterstadt")));
    page.add(
        List.of(
            at(40, "Ihre Abrechnung vom 01.02." + year + " bis 28.02." + year),
            at(300, "Mastercard-Nummer: 1234 56XX XXXX 7890")));
    page.add(List.of(at(40, "Karteninhaber: Erika Beispiel")));
    page.add(
        List.of(
            at(40, "Datum"),
            at(85, "Datum"),
            at(130, "Verwendungszweck"),
            at(440, "Betrag"),
            at(500, "EUR")));
    page.add(List.of(at(40, "Beleg"), at(85, "Buchung")));
    page.add(
        List.of(
            at(40, "01.02" + yy),
            at(130, "Saldo letzte Abrechnung"),
            endingAt(CARD_AMOUNT_END, "1.204,37"),
            at(CARD_SIGN, "-")));
    page.add(cardBooking("03.02" + yy, "05.02" + yy, "Beispiel Laden, Musterstadt", "23,61", "-"));
    page.add(
        cardBooking(
            "09.02" + yy, "10.02" + yy, "Online-Shop Muster, Beispielstadt", "147,93", "-"));
    page.add(cardBooking("17.02" + yy, "17.02" + yy, "Lastschrift", "1.204,37", "+"));
    page.add(
        cardBooking("20.02" + yy, "21.02" + yy, "Caf\u00e9 Beispiel, Musterhausen", "8,47", "-"));
    page.add(
        List.of(at(130, "Neuer Saldo"), endingAt(CARD_AMOUNT_END, "180,01"), at(CARD_SIGN, "-")));
    page.add(
        List.of(
            atFoot(40, "Der Rechnungsbetrag wird Ihrem Konto belastet."),
            at(480, "Seite 1 von 1")));
    return SyntheticStatements.statement(List.of(page));
  }

  private static List<Cell> cardBooking(
      String purchased, String booked, String text, String amount, String sign) {
    return List.of(
        at(40, purchased),
        at(85, booked),
        at(130, text),
        endingAt(CARD_AMOUNT_END, amount),
        at(CARD_SIGN, sign));
  }

  /**
   * An LBS building-savings statement (SPK-7): booking and value date at the start of each booking
   * line, unsigned amounts under Belastungen or Gutschriften, contract details beside the bookings
   * under the "Tarif" label, a dateless "Saldovortrag" and the dated closing balance.
   */
  public static byte[] lbsStatement() throws IOException {
    List<List<Cell>> page = new ArrayList<>();
    page.add(List.of(at(40, "LBS Invented \u00b7 Postfach 00 00")));
    page.add(List.of(at(40, "Bausparvertrag 4 268 135 009")));
    page.add(
        List.of(at(40, "Kontoauszug 2031 Seite Nr. 1 von 1"), at(LBS_PANEL, "Bausparvertrag")));
    page.add(
        List.of(
            at(LBS_BOOKED, "Buch.-"),
            at(LBS_VALUE, "Wert"),
            at(LBS_TEXT, "Buchungsvorgang"),
            at(300, "Belastungen"),
            at(370, "Gutschriften"),
            at(LBS_PANEL, "Tarif CLASSIC")));
    page.add(List.of(at(LBS_BOOKED, "Datum"), at(LBS_PANEL, "Bausparsumme 10.000,00")));
    page.add(
        List.of(
            at(LBS_TEXT, "Saldovortrag"),
            endingAt(LBS_CREDIT_END, "1.234,56"),
            at(LBS_PANEL, "Vertragsbeginn 01.01.2030")));
    page.add(lbsBooking("15.01.2031", "Einzahlung", null, "47,13", "Bewertungszahl 123,45"));
    page.add(
        lbsBooking("15.02.2031", "Einzahlung", null, "52,61", "IBAN DE00 0000 0000 0000 0000 00"));
    page.add(lbsBooking("31.03.2031", "Kontogeb\u00fchr", "11,37", null, "BIC XXXXDEXXXXX"));
    page.add(lbsBooking("31.12.2031", "Zinsen abgeltungsteuerfrei", null, "1,23", null));
    page.add(
        List.of(
            at(LBS_BOOKED, "Ihr Bausparguthaben am 31.12.2031"),
            endingAt(LBS_CREDIT_END, "1.324,16")));
    page.add(List.of(atFoot(40, "LBS Invented, Beispielstrasse 1")));
    return SyntheticStatements.statement(List.of(page));
  }

  private static List<Cell> lbsBooking(
      String date, String text, String debit, String credit, String panel) {
    List<Cell> cells = new ArrayList<>();
    cells.add(at(LBS_BOOKED, date));
    cells.add(at(LBS_VALUE, date));
    cells.add(at(LBS_TEXT, text));
    if (debit != null) {
      cells.add(endingAt(LBS_DEBIT_END, debit));
    }
    if (credit != null) {
      cells.add(endingAt(LBS_CREDIT_END, credit));
    }
    if (panel != null) {
      cells.add(at(LBS_PANEL, panel));
    }
    return cells;
  }

  // --- holdings statements (US-07-13, #273; V100) ------------------------------------------

  /** Invented ISINs with valid check digits; no country uses ZZ, so none is a real security. */
  public static final String HOLDING_ISIN_CHF = "ZZ0000000016";

  public static final String HOLDING_ISIN_USD = "ZZ0000000024";
  public static final String HOLDING_ISIN_FUND = "ZZ0000000032";

  /** The invented crypto symbol of the Yuh-style line, resolved by the workspace's ticker. */
  public static final String HOLDING_CRYPTO = "XQZ";

  /** The statement's date, far in the future so no real statement shares it. */
  public static final String HOLDING_DATE = "31.12.2031";

  /**
   * A "Portfolio-Wertentwicklung" statement in the Swissquote layout (PF-7, YUH-2): title page,
   * overview, then cash (CHF and USD Kontokorrent), a Yuh-style crypto section, equities in CHF and
   * USD (one name continued on a second line, a section total) and a fund in CHF with a fractional
   * quantity, the page furniture repeated on page two.
   */
  public static byte[] swissquoteHoldings() throws IOException {
    List<List<Cell>> first = new ArrayList<>();
    first.add(List.of(at(40, "Portfolio-Wertentwicklung per " + HOLDING_DATE)));
    first.add(List.of(at(40, "Kontonummer 000000")));
    first.add(List.of(at(40, "Bewertet in CHF")));
    first.add(List.of(at(40, "1. Übersicht")));
    first.add(List.of(at(40, "CHF 1'452.51 12.19")));
    first.add(List.of(at(40, "Total 11'915.18 100.00")));
    first.add(holdingsHeading());
    first.add(List.of(at(40, "Liquidität")));
    first.add(List.of(at(40, "Währung Referenz Beschreibung Wert Wechselkurs Bewertung in CHF %")));
    first.add(List.of(at(40, "CHF 00000001 Kontokorrent CHF 1'204.35 1.00000 1'204.35 10.11")));
    first.add(List.of(at(40, "USD 00000002 Kontokorrent USD 310.20 0.80000 248.16 2.08")));
    first.add(List.of(at(40, " Total Liquidität 1'452.51 12.19")));
    first.add(List.of(at(40, "Kryptowährungen")));
    first.add(List.of(at(40, "Währung Beschreibung Wert Wechselkurs (1) Bewertung in CHF %")));
    first.add(
        List.of(
            at(
                40,
                HOLDING_CRYPTO
                    + " Position in "
                    + HOLDING_CRYPTO
                    + " 12.5000000 3.1415926 39.27 0.33")));
    first.add(List.of(at(40, "Aktien in CHF")));
    first.add(List.of(at(40, positionsHeader("CHF"))));
    first.add(
        List.of(
            at(
                40,
                "25 INVENTED HOLDING AG N "
                    + HOLDING_ISIN_CHF
                    + " 101.250 113.400 30.12.2031 2'835.00 23.79")));
    first.add(List.of(at(40, "Total Aktien in CHF 2'835.00 23.79")));
    first.add(List.of(atFoot(40, "Invented Bank AG, Beispielweg 1"), at(480, "Seite 1 / 2")));
    List<List<Cell>> second = new ArrayList<>();
    second.add(holdingsHeading());
    second.add(List.of(at(40, "Aktien in USD")));
    second.add(List.of(at(40, positionsHeader("USD") + " Wechselkurs Bewertung in CHF %")));
    second.add(
        List.of(
            at(
                40,
                "1'200 EXAMPLE TECHNOLOGIES "
                    + HOLDING_ISIN_USD
                    + " 4.105 4.370 30.12.2031 5'244.00 4'195.20 35.21")));
    second.add(List.of(at(40, "ORD")));
    second.add(List.of(at(40, "Total Aktien in USD 5'244.00 0.80000 4'195.20 35.21")));
    second.add(List.of(at(40, "Anlagefonds in CHF")));
    second.add(List.of(at(40, positionsHeader("CHF"))));
    second.add(
        List.of(
            at(
                40,
                "12.3456 SAMPLE WORLD ETF "
                    + HOLDING_ISIN_FUND
                    + " 87.650 95.120 30.12.2031 1'174.31 9.86")));
    second.add(List.of(at(40, "GESAMTVERMÖGEN IN CHF 11'915.18")));
    second.add(List.of(atFoot(40, "Invented Bank AG, Beispielweg 1"), at(480, "Seite 2 / 2")));
    return SyntheticStatements.statement(List.of(first, second));
  }

  private static List<Cell> holdingsHeading() {
    return List.of(at(40, "Portfolio-Wertentwicklung per " + HOLDING_DATE + " - Erika Beispiel"));
  }

  // A section's table header; a foreign section's header continues with the CHF columns.
  private static String positionsHeader(String currency) {
    String header =
        "Anzahl Wertpapier ISIN Durchschnittspreis Marktpreis Kursdatum Bewertung in " + currency;
    return "CHF".equals(currency) ? header + " %" : header;
  }

  /** The tickers of {@link #postfinancePositionsWorkbook}. */
  public static final String WORKBOOK_TICKER_CHF = "INVT";

  public static final String WORKBOOK_TICKER_USD = "EXMP";
  public static final String WORKBOOK_TICKER_ETF = "SMPW";

  /**
   * An E-Trading positions export (PF-5) as .xlsx: the shipped header row, then group headings,
   * positions by ticker (numbers stored as numbers, one value a binary double with a rounding
   * tail), subtotals and a total - which have no quantity.
   */
  public static byte[] postfinancePositionsWorkbook() throws IOException {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Positionen");
      row(
          sheet,
          0,
          "",
          "Symbol",
          "Anzahl",
          "Einstandskurs",
          "Totalwert",
          "Tagesveränderung",
          "Diff. Vortag %",
          "Preis",
          "Währung",
          "G&V Nominalwert CHF",
          "G&V % CHF",
          "Totalwert CHF",
          "Positionen %");
      row(sheet, 1, "Aktien");
      row(
          sheet,
          2,
          "",
          WORKBOOK_TICKER_CHF,
          25,
          101.25,
          2835.0,
          "",
          "",
          113.4,
          "CHF",
          303.75,
          0.12,
          2835.0,
          0.4);
      row(
          sheet,
          3,
          "",
          WORKBOOK_TICKER_USD,
          1200,
          4.105,
          5244.000000000001,
          "",
          "",
          4.37,
          "USD",
          254.4,
          0.06,
          4195.2,
          0.5);
      row(
          sheet,
          4,
          "",
          "Zwischensumme Aktien in CHF",
          "",
          "",
          "",
          "",
          "",
          "",
          "",
          558.15,
          0.08,
          7030.2,
          0.9);
      row(sheet, 5, "ETFs");
      row(
          sheet,
          6,
          "",
          WORKBOOK_TICKER_ETF,
          12.3456,
          87.65,
          1174.31,
          "",
          "",
          95.12,
          "CHF",
          92.2,
          0.09,
          1174.31,
          0.1);
      row(sheet, 7, "", "Gesamt", "", "", "", "", "", "", "", 650.35, 0.08, 8204.51, 1.0);
      return bytes(workbook);
    }
  }

  /**
   * An E-Trading cash export (PF-4) as binary .xls: one row per currency with its account balance,
   * then the total in CHF, which names no currency code.
   */
  public static byte[] postfinanceCashWorkbook() throws IOException {
    try (Workbook workbook = new HSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Liquiditaet");
      row(
          sheet,
          0,
          "Währung",
          "Kurs",
          "Kontosaldo",
          "Positionswert",
          "Totalwert",
          "Bewertung CHF",
          "Portfolio %");
      row(sheet, 1, "CHF", 1.0, 1204.35, 4009.31, 5213.66, 5213.66, 0.62);
      row(sheet, 2, "USD", 0.8, 310.2, 5244.0, 5554.2, 4443.36, 0.38);
      row(sheet, 3, "Gesamt CHF", " ", 1452.51, 8204.51, 9657.02, 9657.02, 1.0);
      return bytes(workbook);
    }
  }

  // A row of text and number cells; an empty string is a blank text cell.
  private static void row(Sheet sheet, int index, Object... values) {
    Row row = sheet.createRow(index);
    for (int column = 0; column < values.length; column++) {
      Object value = values[column];
      if (value instanceof Number number) {
        row.createCell(column).setCellValue(number.doubleValue());
      } else {
        row.createCell(column).setCellValue((String) value);
      }
    }
  }

  private static byte[] bytes(Workbook workbook) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    workbook.write(output);
    return output.toByteArray();
  }
}
