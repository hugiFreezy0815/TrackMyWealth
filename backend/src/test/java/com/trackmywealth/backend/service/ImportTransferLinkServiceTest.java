package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportRowPair;
import com.trackmywealth.backend.dto.ImportTransferPlan;
import com.trackmywealth.backend.entity.Account;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** US-07-22 (#325): which rows of an import its commit links as exchanges and pocket moves. */
class ImportTransferLinkServiceTest {

  private static final LocalDate DAY = LocalDate.of(2031, 3, 7);
  private static final UUID POCKET = UUID.randomUUID();

  private final Account account = mock(Account.class);

  @Test
  void twoLegsSharingAReferenceAndDayInTwoCurrenciesAreOneExchangeDebitFirst() {
    ImportTransferPlan plan =
        ImportTransferLinkService.plan(
            account,
            List.of(
                row("R-17", DAY, "41.37", "EUR", "INCOME"),
                row(null, DAY, "-8.13", "CHF", "EXPENSE"),
                row("R-17", DAY, "-39.71", "CHF", "EXPENSE")),
            null);

    assertThat(plan.exchanges()).containsExactly(new ImportRowPair(2, 0));
    assertThat(plan.pocketMoves()).isEmpty();
  }

  @Test
  void aSharedReferenceOfOneCurrencyIsNoExchange() {
    // Like a collective booking whose entries all carry the day's one bank reference.
    ImportTransferPlan plan =
        ImportTransferLinkService.plan(
            account,
            List.of(
                row("R-23", DAY, "-12.31", "EUR", "EXPENSE"),
                row("R-23", DAY, "12.31", "EUR", "INCOME")),
            null);

    assertThat(plan.exchanges()).isEmpty();
  }

  @Test
  void aReferenceOnMoreOrFewerThanTwoLegsPairsNothing() {
    ImportTransferPlan plan =
        ImportTransferLinkService.plan(
            account,
            List.of(
                row("R-31", DAY, "-10.07", "CHF", "EXPENSE"),
                row("R-31", DAY, "10.43", "EUR", "INCOME"),
                row("R-31", DAY, "-0.29", "CHF", "EXPENSE"),
                row("R-37", DAY, "-5.11", "CHF", "EXPENSE")),
            null);

    assertThat(plan.exchanges()).isEmpty();
  }

  @Test
  void legsOfOneDirectionOrOfTwoDaysPairNothing() {
    ImportTransferPlan plan =
        ImportTransferLinkService.plan(
            account,
            List.of(
                row("R-41", DAY, "-7.19", "CHF", "EXPENSE"),
                row("R-41", DAY, "-7.53", "EUR", "EXPENSE"),
                row("R-43", DAY, "-9.17", "CHF", "EXPENSE"),
                row("R-43", DAY.plusDays(1), "9.61", "EUR", "INCOME")),
            null);

    assertThat(plan.exchanges()).isEmpty();
  }

  @Test
  void onlyRowsTransferMatchingWouldTakeAsLegsPair() {
    // A fee or a dividend is no leg of an exchange, whatever reference it carries.
    ImportTransferPlan plan =
        ImportTransferLinkService.plan(
            account,
            List.of(
                row("R-47", DAY, "-3.19", "CHF", "FEE"),
                row("R-47", DAY, "3.37", "EUR", "INCOME"),
                row("R-53", DAY, "-6.29", "CHF", "WITHDRAWAL"),
                row("R-53", DAY, "6.71", "EUR", "DEPOSIT")),
            null);

    assertThat(plan.exchanges()).containsExactly(new ImportRowPair(2, 3));
  }

  @Test
  void savingsPocketMovesAreLinkedToThePocketAndNeverPairedAsAnExchange() {
    ImportTransferPlan plan =
        ImportTransferLinkService.plan(
            account,
            List.of(
                pocketMove("R-59", "-17.35"),
                row("R-59", DAY, "17.89", "EUR", "INCOME"),
                pocketMove(null, "-4.21")),
            POCKET);

    assertThat(plan.pocketMoves()).containsExactly(0, 2);
    assertThat(plan.exchanges()).isEmpty();
    assertThat(plan.savingsPocketAccountId()).isEqualTo(POCKET);
    assertThat(plan.isEmpty()).isFalse();
  }

  @Test
  void pocketMovesWithoutAPocketLeaveNothingToLink() {
    ImportTransferPlan plan =
        ImportTransferLinkService.plan(account, List.of(pocketMove(null, "-17.35")), null);

    assertThat(plan.pocketMoves()).containsExactly(0);
    assertThat(plan.isEmpty()).as("the move stays a one-sided transfer").isTrue();
  }

  @Test
  void aCardOrALoanLinksNothing() {
    List<CanonicalImportRow> rows =
        List.of(
            row("R-61", DAY, "-11.13", "CHF", "EXPENSE"),
            row("R-61", DAY, "11.59", "EUR", "INCOME"),
            pocketMove(null, "-2.17"));
    Account card = mock(Account.class);
    when(card.isHasStatementCycle()).thenReturn(true);
    Account loan = mock(Account.class);
    when(loan.isHasAmortisation()).thenReturn(true);

    assertThat(ImportTransferLinkService.plan(card, rows, POCKET).isEmpty()).isTrue();
    assertThat(ImportTransferLinkService.plan(loan, rows, POCKET).isEmpty()).isTrue();
  }

  private static CanonicalImportRow row(
      String groupReference, LocalDate date, String amount, String currency, String type) {
    return new CanonicalImportRow(
        date,
        null,
        new BigDecimal(amount),
        currency,
        type,
        "Invented booking",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        groupReference,
        false);
  }

  private static CanonicalImportRow pocketMove(String groupReference, String amount) {
    return new CanonicalImportRow(
        DAY,
        null,
        new BigDecimal(amount),
        "CHF",
        "TRANSFER",
        "Spareinlage",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        groupReference,
        true);
  }
}
