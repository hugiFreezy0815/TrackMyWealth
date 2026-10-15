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
