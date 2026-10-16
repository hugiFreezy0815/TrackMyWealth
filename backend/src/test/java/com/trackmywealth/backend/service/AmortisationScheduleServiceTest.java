package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.entity.AmortisationScheduleEntry;
import com.trackmywealth.backend.repository.AmortisationScheduleEntryRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * US-10-02: which schedule entry splits a payment, and which one states the balance an estimate
 * starts from. Access is the caller's ({@link LoanRepaymentService}), so it is not checked here.
 */
class AmortisationScheduleServiceTest {

  private static final UUID LOAN = UUID.randomUUID();

  private final AmortisationScheduleEntryRepository repository =
      mock(AmortisationScheduleEntryRepository.class);
  private final AmortisationScheduleService service =
      new AmortisationScheduleService(
          mock(AccountLookupService.class),
          mock(AccessControlService.class),
          repository,
          mock(VersionPreconditionService.class));

  @Test
  void thePaymentsMonthIsSearchedAndTheNearestEntryInItWins() {
    AmortisationScheduleEntry early = entry("2026-03-01");
    AmortisationScheduleEntry late = entry("2026-03-28");
    when(repository.findByAccountIdAndDueDateBetweenOrderByDueDateAsc(
            LOAN, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31")))
        .thenReturn(List.of(early, late));

    assertThat(service.entryForPayment(LOAN, LocalDate.parse("2026-03-25"))).contains(late);
    assertThat(service.entryForPayment(LOAN, LocalDate.parse("2026-03-05"))).contains(early);
  }

  @Test
  void onATieTheEarlierEntryWins() {
    AmortisationScheduleEntry early = entry("2026-03-10");
    AmortisationScheduleEntry late = entry("2026-03-20");
    when(repository.findByAccountIdAndDueDateBetweenOrderByDueDateAsc(
            LOAN, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31")))
        .thenReturn(List.of(early, late));

    assertThat(service.entryForPayment(LOAN, LocalDate.parse("2026-03-15"))).contains(early);
  }

  @Test
  void noEntryThatMonthSplitsNothing() {
    assertThat(service.entryForPayment(LOAN, LocalDate.parse("2026-03-15"))).isEmpty();
  }

  @Test
  void theBalanceForAnEstimateComesFromTheLatestEntryBeforeThePaymentsMonth() {
    // An entry of the payment's own month would have split it; only earlier ones state a balance.
    AmortisationScheduleEntry february = entry("2026-02-28");
    when(repository.findFirstByAccountIdAndDueDateBeforeOrderByDueDateDesc(
            LOAN, LocalDate.parse("2026-03-01")))
        .thenReturn(Optional.of(february));

    assertThat(service.latestEntryBefore(LOAN, LocalDate.parse("2026-03-15"))).contains(february);
    verify(repository)
        .findFirstByAccountIdAndDueDateBeforeOrderByDueDateDesc(
            LOAN, LocalDate.parse("2026-03-01"));
  }

  private static AmortisationScheduleEntry entry(String dueDate) {
    AmortisationScheduleEntry entry = mock(AmortisationScheduleEntry.class);
    when(entry.getDueDate()).thenReturn(LocalDate.parse(dueDate));
    return entry;
  }
}
