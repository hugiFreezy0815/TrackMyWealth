package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AmortisationScheduleEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AmortisationScheduleEntryRepository
    extends JpaRepository<AmortisationScheduleEntry, UUID> {

  List<AmortisationScheduleEntry> findByAccountIdOrderByDueDateAsc(UUID accountId);

  Optional<AmortisationScheduleEntry> findByIdAndAccountId(UUID id, UUID accountId);

  /** US-10-02: the entries due in [{@code from}, {@code to}], earliest first. */
  List<AmortisationScheduleEntry> findByAccountIdAndDueDateBetweenOrderByDueDateAsc(
      UUID accountId, LocalDate from, LocalDate to);

  /** US-10-02: the latest entry due before {@code date}. */
  Optional<AmortisationScheduleEntry> findFirstByAccountIdAndDueDateBeforeOrderByDueDateDesc(
      UUID accountId, LocalDate date);
}
