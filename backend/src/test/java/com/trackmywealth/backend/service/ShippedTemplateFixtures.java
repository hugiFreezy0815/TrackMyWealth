package com.trackmywealth.backend.service;

import static com.trackmywealth.backend.service.SyntheticStatements.at;
import static com.trackmywealth.backend.service.SyntheticStatements.atFoot;
import static com.trackmywealth.backend.service.SyntheticStatements.endingAt;
import static com.trackmywealth.backend.service.SyntheticStatements.strayBlank;

import com.trackmywealth.backend.service.SyntheticStatements.Cell;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * US-07-15 (#275): synthetic PDF statements in the layouts of the shipped PDF templates (V77-V79,
 * docs/architecture/import-source-analysis.md 6.8), named by each template's fixture_reference.
 * They reproduce the structure of the real statements - markers, header labels, balance lines,
 * columns, page furniture - with invented values only. A real statement is never used.
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
            at(40, "Darlehen 0000000000, DE00 0000 0000 0000 0000 00"),
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
   * An LBS building-savings statement (SPK-7): booking and value date at the start of each booking
   * line, unsigned amounts under Belastungen or Gutschriften, contract details beside the bookings
   * under the "Tarif" label, a dateless "Saldovortrag" and the dated closing balance.
   */
  public static byte[] lbsStatement() throws IOException {
    List<List<Cell>> page = new ArrayList<>();
    page.add(List.of(at(40, "LBS Invented \u00b7 Postfach 00 00")));
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
}
