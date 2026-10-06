package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.trackmywealth.backend.dto.ImportPdfBookingLine;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.error.ImportRowRejectedException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #268: the running balance rules on their own, without a statement; {@link PdfStatementLayoutTest}
 * covers them on synthetic statements.
 */
class PdfBalanceCheckServiceTest {

  private final PdfBalanceCheckService balances = new PdfBalanceCheckService();
  private final ImportTemplateDefinition template =
      new ImportFileParserServiceTest.Template().thousands("'").build();

  /** A booking leads from the balance before it to the one it states, the next one from there. */
  @Test
  void aBookingLeadsToTheBalanceItStates() {
    BigDecimal after =
        balances.balanceAfter(
            new BigDecimal("100.00"), new BigDecimal("-20.00"), "80.00", template);

    assertThat(after).isEqualByComparingTo("80.00");
  }

  /**
   * The mismatch names the stated balance first and the expected one second, as the message's {0}
   * and {1} read them.
   */
  @Test
  void anotherStatedBalanceIsAMismatchInTheMessagesOrder() {
    assertThatThrownBy(
            () ->
                balances.balanceAfter(
                    new BigDecimal("100.00"), new BigDecimal("-20.00"), "1'080.00", template))
        .isInstanceOfSatisfying(
            ImportRowRejectedException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ImportRowErrorValues.BALANCE_MISMATCH);
              assertThat(e.getArgs())
                  .containsExactly(entry("value", "1080.00"), entry("expected", "80.00"));
            });
  }

  /**
   * A balance that is none, or no amount the template reads (a trailing minus, a debit suffix),
   * states nothing: the booking is not checked, and the running balance goes on from its amount.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "80.00-", "80.00 S", "n/a"})
  void aBalanceThatIsNoAmountChecksNothing(String balance) {
    BigDecimal after =
        balances.balanceAfter(
            new BigDecimal("100.00"), new BigDecimal("-20.00"), balance, template);

    assertThat(after).isEqualByComparingTo("80.00");
    assertThat(balances.read(balance, template)).isNull();
  }

  /** Without a balance before it, a booking is not checked and starts from the one it states. */
  @Test
  void withoutABalanceBeforeTheStatedOneStarts() {
    assertThat(balances.balanceAfter(null, new BigDecimal("-20.00"), "55.00", template))
        .isEqualByComparingTo("55.00");
    assertThat(balances.balanceAfter(null, new BigDecimal("-20.00"), null, template)).isNull();
  }

  /** With negative amounts in parentheses, a negative balance is written in them too. */
  @Test
  void aBalanceInParenthesesFollowsTheAmountRepresentation() {
    ImportTemplateDefinition parentheses =
        new ImportFileParserServiceTest.Template()
            .amountRepresentation("NEGATIVE_IN_PARENTHESES")
            .build();

    assertThat(balances.read("(1100.00)", parentheses)).isEqualByComparingTo("-1100.00");
    assertThat(balances.read("(1100.00)", template)).isNull();
  }

  /**
   * The balance before a booking: a balance line's since the booking before, else none at a
   * section's start, else the running one.
   */
  @Test
  void theBalanceBeforeABookingIsTheLastOneStated() {
    BigDecimal running = new BigDecimal("10.00");

    assertThat(balances.balanceBefore(booking(false, "25.00"), running, template))
        .isEqualByComparingTo("25.00");
    assertThat(balances.balanceBefore(booking(true, "25.00"), running, template))
        .isEqualByComparingTo("25.00");
    assertThat(balances.balanceBefore(booking(true, null), running, template)).isNull();
    assertThat(balances.balanceBefore(booking(false, null), running, template))
        .isEqualByComparingTo("10.00");
  }

  /**
   * Each balance line below a booking that states another balance than the bookings lead to is an
   * error row of its own, numbered on from the given row; the ones that add up, or are no amount,
   * are none.
   */
  @Test
  void balanceLinesThatDoNotAddUpAreErrorRows() {
    ImportPdfBookingLine booking =
        ImportPdfBookingLine.builder(
                "02.01.2031 Lohn", List.of("x"), ImportPdfBookingLine.Status.MATCHED)
            .withBalancesAfter(
                List.of(
                    new ImportPdfBookingLine.BalanceLine("Uebertrag 90.00", "90.00"),
                    new ImportPdfBookingLine.BalanceLine("Uebertrag 91.00", "91.00"),
                    new ImportPdfBookingLine.BalanceLine("Saldo --", "--"),
                    new ImportPdfBookingLine.BalanceLine("Saldo 92.00", "92.00")))
            .build();

    List<ParsedImportRow> errors =
        balances.balanceLineErrors(booking, new BigDecimal("90.00"), 7, template);

    assertThat(errors).extracting(ParsedImportRow::rowNumber).containsExactly(7, 8);
    assertThat(errors)
        .extracting(ParsedImportRow::errorCode)
        .containsOnly(ImportRowErrorValues.BALANCE_LINE_MISMATCH);
    assertThat(errors.get(0).errorArgs())
        .containsExactly(entry("value", "91.00"), entry("expected", "90.00"));
    assertThat(errors.get(1).rawData())
        .containsExactly(entry(ImportFileParserService.RAW_LINE_KEY, "Saldo 92.00"));
    assertThat(balances.balanceLineErrors(booking, null, 7, template)).isEmpty();
  }

  private static ImportPdfBookingLine booking(boolean sectionStart, String statedBalance) {
    return ImportPdfBookingLine.builder(
            "02.01.2031 Lohn", List.of("x"), ImportPdfBookingLine.Status.MATCHED)
        .withSectionStart(sectionStart)
        .withStatedBalance(statedBalance)
        .build();
  }
}
