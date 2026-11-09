package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.SettlementMatchRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #482: the current statement date a card row shows, and the due date derived from it. Both need no
 * account lookup: the Portfolio View passes the extensions it already loaded and authorized.
 */
class CardStatementServiceTest {

  private final BusinessDateService businessDateService = mock(BusinessDateService.class);
  private final CardStatementService service =
      new CardStatementService(
          mock(AccountLookupService.class),
          mock(AccessControlService.class),
          mock(AccountCreditCardRepository.class),
          mock(SettlementMatchRepository.class),
          mock(AccountValuationService.class),
          businessDateService,
          mock(VersionPreconditionService.class));

  @ParameterizedTest
  @CsvSource({
    // A cycle closing on the 28th: before, on and after the closing day.
    "2026-11-08, 28, 2026-10-28",
    "2026-10-28, 28, 2026-10-28",
    "2026-10-29, 28, 2026-10-28",
    // Across a year end.
    "2026-01-05, 28, 2025-12-28",
    // A day the month does not have closes on its last day.
    "2026-03-01, 31, 2026-02-28",
    "2026-02-28, 31, 2026-02-28",
    "2028-02-29, 30, 2028-02-29"
  })
  void theStatementDateIsTheMostRecentClosingOnOrBeforeToday(
      LocalDate today, short statementDay, LocalDate expected) {
    when(businessDateService.today()).thenReturn(today);

    assertThat(service.currentStatementDate(card(statementDay, null))).contains(expected);
  }

  @Test
  void theDueDateIsTheSameStatementDatePlusItsOffset() {
    when(businessDateService.today()).thenReturn(LocalDate.of(2026, 11, 8));

    AccountCreditCard card = card((short) 28, (short) 20);

    assertThat(service.currentStatementDate(card)).contains(LocalDate.of(2026, 10, 28));
    assertThat(service.currentDueDate(card)).contains(LocalDate.of(2026, 11, 17));
  }

  @Test
  void aStatementDayAloneGivesAStatementDateButNoDueDate() {
    when(businessDateService.today()).thenReturn(LocalDate.of(2026, 11, 8));

    AccountCreditCard card = card((short) 28, null);

    assertThat(service.currentStatementDate(card)).isPresent();
    assertThat(service.currentDueDate(card)).isEmpty();
  }

  @Test
  void withoutAStatementDayThereIsNeitherDate() {
    AccountCreditCard card = card(null, (short) 20);

    assertThat(service.currentStatementDate(card)).isEmpty();
    assertThat(service.currentDueDate(card)).isEmpty();
  }

  private static AccountCreditCard card(Short statementDay, Short dueDateOffsetDays) {
    AccountCreditCard card = new AccountCreditCard();
    card.setStatementDay(statementDay);
    card.setDueDateOffsetDays(dueDateOffsetDays);
    return card;
  }
}
