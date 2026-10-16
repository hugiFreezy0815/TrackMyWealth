package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.ImportStatementBalanceRule;
import com.trackmywealth.backend.dto.ImportStatementBalances;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * US-07-12: reading the balances a file states, and checking a template's statement balance rule.
 * The lines are synthetic; which balance becomes a snapshot is {@link
 * ImportStatementBalanceServiceTest}'s.
 */
class ImportStatementBalanceReaderServiceTest {

  // A Yuh-like balance line: Swiss notation, a currency after the amount.
  private static final String SALDO =
      "^Saldo per (?P<date>\\S+)\\s+(?P<amount>-?[0-9'.]+) (?P<currency>\\S+)$";

  private final ImportStatementBalanceReaderService reader =
      new ImportStatementBalanceReaderService();

  @Test
  void eachMatchingLineIsABalanceWithItsDateAmountAndCurrency() {
    ImportStatementBalances read =
        reader.read(
            List.of(
                "Kontoauszug",
                "Saldo per 01.01.2031   263.40 CHF",
                "02.01.2031 Coffee 4.50",
                "Saldo per 31.01.2031 1'248.55 chf",
                "Saldo per 31.01.2031 60.00 EUR"),
            swiss(SALDO));

    assertThat(read.balances())
        .containsExactly(
            new ImportStatementBalances.Stated(
                LocalDate.of(2031, 1, 1), new BigDecimal("263.40"), "CHF"),
            new ImportStatementBalances.Stated(
                LocalDate.of(2031, 1, 31), new BigDecimal("1248.55"), "CHF"),
            new ImportStatementBalances.Stated(
                LocalDate.of(2031, 1, 31), new BigDecimal("60.00"), "EUR"));
    assertThat(read.unreadable()).isZero();
  }

  /** A matched line is never silently dropped: what cannot be read is counted. */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Saldo per 31.02.2031 10.00 CHF", // no such date
        "Saldo per 31.01.2031 1.2.3 CHF", // no amount
        "Saldo per 31.01.2031 10.00 FRANKEN" // no currency code
      })
  void aMatchedLineWhoseValuesCannotBeReadIsCountedUnreadable(String line) {
    ImportStatementBalances read = reader.read(List.of(line), swiss(SALDO));

    assertThat(read.balances()).isEmpty();
    assertThat(read.unreadable()).isEqualTo(1);
  }

  /** An unreadable line keeps the currency it names, so it hides only that currency's balance. */
  @Test
  void anUnreadableLineIsAttributedToTheCurrencyItNamesWhenThatIsReadable() {
    ImportStatementBalances read =
        reader.read(
            List.of(
                "Saldo per 31.01.2031 1.2.3 EUR",
                "Saldo per 31.02.2031 10.00 CHF",
                "Saldo per 31.01.2031 10.00 FRANKEN"),
            swiss(SALDO));

    assertThat(read.unreadableCurrencies()).containsExactly("EUR", "CHF", null);
    assertThat(read.unreadableIn("EUR")).isEqualTo(2);
    assertThat(read.unreadableIn("USD")).isEqualTo(1);
  }

  @Test
  void anOptionalCurrencyGroupThatDidNotMatchLeavesTheCurrencyOpen() {
    ImportStatementBalances read =
        reader.read(
            List.of("Saldo per 31.01.2031 10.00"),
            swiss("^Saldo per (?P<date>\\S+) (?P<amount>[0-9.]+)(?: (?P<currency>[A-Z]{3}))?$"));

    assertThat(read.balances())
        .containsExactly(
            new ImportStatementBalances.Stated(
                LocalDate.of(2031, 1, 31), new BigDecimal("10.00"), null));
  }

  @Test
  void anAmountInParenthesesIsNegativeForSuchATemplate() {
    ImportTemplateDefinition template =
        new ImportFileParserServiceTest.Template()
            .preamble(1)
            .amountRepresentation("NEGATIVE_IN_PARENTHESES")
            .statementBalance(
                new ImportStatementBalanceRule(
                    "^Balance (?P<date>\\S+) (?P<amount>\\S+)$", "dd.MM.yyyy"))
            .build();

    assertThat(reader.read(List.of("Balance 31.01.2031 (12.50)"), template).balances())
        .extracting(ImportStatementBalances.Stated::amount)
        .containsExactly(new BigDecimal("-12.50"));
  }

  @Test
  void theTemplatesOwnDateFormatAppliesWithoutOneOfTheRules() {
    ImportTemplateDefinition template =
        new ImportFileParserServiceTest.Template()
            .preamble(1)
            .statementBalance(
                new ImportStatementBalanceRule("^Balance (?P<date>\\S+) (?P<amount>\\S+)$", null))
            .build();

    assertThat(reader.read(List.of("Balance 2031-01-31 7.00"), template).balances())
        .extracting(ImportStatementBalances.Stated::date)
        .containsExactly(LocalDate.of(2031, 1, 31));
  }

  @Test
  void aTemplateWithoutTheRuleReadsNothingAndPassesItsCheck() {
    ImportTemplateDefinition template = new ImportFileParserServiceTest.Template().build();

    assertThat(reader.read(List.of("Saldo per 31.01.2031 1.00 CHF"), template)).isNull();
    assertThatCode(() -> reader.validate(template)).doesNotThrowAnyException();
  }

  @Test
  void aValidRuleOnACsvTemplateWithAPreamblePassesItsCheck() {
    assertThatCode(() -> reader.validate(swiss(SALDO))).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      nullValues = "NULL",
      value = {
        "NULL|NULL|statementBalance.pattern",
        "'  '|NULL|statementBalance.pattern",
        "(?P<date>x)|NULL|statementBalance.pattern", // no amount group
        "(?P<amount>x)|NULL|statementBalance.pattern", // no date group
        "(?P<date>x)(?P<amount>y)(?P<curency>z)|NULL|statementBalance.pattern", // a typo
        "(?P<date>x)(?P<amount>[y)|NULL|statementBalance.pattern", // not RE2
        "(?:(?P<date>a{1000}){1000})(?P<amount>y)|NULL|statementBalance.pattern", // too large
        "(?P<date>x)(?P<amount>y)|dd.MM.qq|statementBalance.dateFormat"
      })
  void anInvalidRuleIsA422NamingItsField(String pattern, String dateFormat, String field) {
    ImportTemplateDefinition template =
        new ImportFileParserServiceTest.Template()
            .preamble(1)
            .statementBalance(new ImportStatementBalanceRule(pattern, dateFormat))
            .build();

    assertThatThrownBy(() -> reader.validate(template))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_INVALID);
              assertThat(e.getBody().getProperties()).containsEntry("field", field);
            });
  }

  // #339: a Sparkasse-card-like statement - the sign printed after the amount, and a closing line
  // without a date of its own, dated by the end of the statement's period.
  private static final ImportStatementBalanceRule CARD_RULE =
      new ImportStatementBalanceRule(
          "^(?:(?P<date>[0-9]{2}[.][0-9]{2}[.][0-9]{2}) Saldo letzte Abrechnung|Neuer Saldo)"
              + " (?P<amount>[0-9.]*[0-9],[0-9]{2}) (?P<sign>[+-])$",
          null,
          "Abrechnung vom [0-9.]+ bis (?P<date>[0-9]{2}[.][0-9]{2}[.][0-9]{4})",
          "dd.MM.yyyy");

  // An amount that may carry its own sign, and an optional sign after it.
  private static final ImportStatementBalanceRule SIGN_RULE =
      new ImportStatementBalanceRule(
          "^Saldo (?P<date>\\S+) (?P<amount>[0-9.,-]+)(?: (?P<sign>\\S))?$", null);

  @Test
  void aSignAfterTheAmountSignsItAndALineWithoutADateTakesTheStatementDate() {
    ImportStatementBalances read =
        reader.read(
            List.of(
                "Ihre Abrechnung vom 01.02.2031 bis 28.02.2031 Mastercard-Nummer: 1234 56XX XXXX"
                    + " 7890",
                "01.02.31 Saldo letzte Abrechnung 1.204,37 -",
                "03.02.31 05.02.31 Beispiel Laden, Musterstadt 23,61 -",
                "17.02.31 17.02.31 Lastschrift 1.204,37 +",
                "Neuer Saldo 23,61 -",
                "Saldo zum Vergleich 5,00 +"),
            card(CARD_RULE));

    assertThat(read.balances())
        .containsExactly(
            new ImportStatementBalances.Stated(
                LocalDate.of(2031, 2, 1), new BigDecimal("-1204.37"), null),
            new ImportStatementBalances.Stated(
                LocalDate.of(2031, 2, 28), new BigDecimal("-23.61"), null));
    assertThat(read.unreadable()).isZero();
  }

  @Test
  void aPlusSignOrNoneLeavesTheAmountAsItIsAndAnyOtherSignIsUnreadable() {
    ImportStatementBalances read =
        reader.read(
            List.of("Saldo 01.02.31 12,50 +", "Saldo 02.02.31 -7,25", "Saldo 03.02.31 9,00 x"),
            card(SIGN_RULE));

    assertThat(read.balances())
        .extracting(ImportStatementBalances.Stated::amount)
        .containsExactly(new BigDecimal("12.50"), new BigDecimal("-7.25"));
    assertThat(read.unreadable()).isEqualTo(1);
  }

  // A sign after an amount that is signed itself could contradict it: neither is guessed.
  @Test
  void anAmountSignedTwiceIsUnreadable() {
    ImportStatementBalances read =
        reader.read(
            List.of("Saldo 01.02.31 -7,25 +", "Saldo 02.02.31 -7,25 -", "Saldo 03.02.31 7,25 -"),
            card(SIGN_RULE));

    assertThat(read.balances())
        .extracting(ImportStatementBalances.Stated::amount)
        .containsExactly(new BigDecimal("-7.25"));
    assertThat(read.unreadable()).isEqualTo(2);
  }

  // The first statement date that can be read counts; an unreadable one before it is skipped.
  @Test
  void aStatementDateThatCannotBeReadIsPassedOverForTheNextOne() {
    ImportStatementBalances read =
        reader.read(
            List.of(
                "Ihre Abrechnung vom 01.02.2031 bis 99.99.2031",
                "Ihre Abrechnung vom 01.03.2031 bis 31.03.2031",
                "Neuer Saldo 23,61 -"),
            card(CARD_RULE));

    assertThat(read.balances())
        .containsExactly(
            new ImportStatementBalances.Stated(
                LocalDate.of(2031, 3, 31), new BigDecimal("-23.61"), null));
    assertThat(read.unreadable()).isZero();
  }

  @Test
  void aLineWithoutADateIsUnreadableWhenNoStatementDateIsFound() {
    ImportStatementBalances read =
        reader.read(
            List.of("Neuer Saldo 23,61 -", "Abrechnung vom x bis 31.02.2031"), card(CARD_RULE));

    assertThat(read.balances()).isEmpty();
    assertThat(read.unreadable()).isEqualTo(1);
  }

  @Test
  void aRuleWithAStatementDateNeedsNoDateGroupAndPassesItsCheck() {
    assertThatCode(
            () ->
                reader.validate(
                    card(
                        new ImportStatementBalanceRule(
                            "^Neuer Saldo (?P<amount>\\S+) (?P<sign>[+-])$",
                            null,
                            "bis (?P<date>\\S+)",
                            null))))
        .doesNotThrowAnyException();
    assertThatCode(() -> reader.validate(card(CARD_RULE))).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      nullValues = "NULL",
      value = {
        "(?P<amount>x)|NULL|NULL|statementBalance.pattern", // no date at all
        "(?P<amount>x)|bis (\\S+)|NULL|statementBalance.statementDatePattern", // no date group
        "(?P<amount>x)|(?P<date>x)(?P<amount>y)|NULL|statementBalance.statementDatePattern",
        "(?P<amount>x)|bis (?P<date>[x)|NULL|statementBalance.statementDatePattern", // not RE2
        "(?P<amount>x)|'  '|NULL|statementBalance.statementDatePattern",
        "(?P<amount>x)|bis (?P<date>\\S+)|dd.MM.qq|statementBalance.statementDateFormat",
        "(?P<date>x)(?P<amount>y)|NULL|dd.MM.yyyy|statementBalance.statementDateFormat",
        "(?P<date>x)(?P<amount>y)(?P<signs>z)|NULL|NULL|statementBalance.pattern" // a typo
      })
  void anInvalidStatementDateOrSignIsA422NamingItsField(
      String pattern, String statementDatePattern, String statementDateFormat, String field) {
    ImportTemplateDefinition template =
        card(
            new ImportStatementBalanceRule(
                pattern, null, statementDatePattern, statementDateFormat));

    assertThatThrownBy(() -> reader.validate(template))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_INVALID);
              assertThat(e.getBody().getProperties()).containsEntry("field", field);
            });
  }

  // A template in German notation with two-digit years, whose rule is rule.
  private static ImportTemplateDefinition card(ImportStatementBalanceRule rule) {
    return new ImportFileParserServiceTest.Template()
        .preamble(1)
        .decimal(",")
        .thousands(".")
        .dateFormat("dd.MM.yy")
        .statementBalance(rule)
        .build();
  }

  @Test
  void aPatternLongerThanAnyLayoutPatternIsRefused() {
    ImportTemplateDefinition template =
        new ImportFileParserServiceTest.Template()
            .preamble(1)
            .statementBalance(
                new ImportStatementBalanceRule("(?P<date>x)(?P<amount>y)" + "z".repeat(1000), null))
            .build();

    assertThatThrownBy(() -> reader.validate(template)).isInstanceOf(ApiException.class);
  }

  // A CSV template in Swiss notation, one preamble line, whose rule is pattern.
  private static ImportTemplateDefinition swiss(String pattern) {
    return new ImportFileParserServiceTest.Template()
        .preamble(1)
        .thousands("'")
        .statementBalance(new ImportStatementBalanceRule(pattern, "dd.MM.yyyy"))
        .build();
  }
}
