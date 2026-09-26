package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * A plain unit test, not an integration one: every branch here is a pure translation from an
 * exception instance to a {@link ProblemDetail}, with no need for a DB or Spring context to
 * exercise. This is what actually verifies the {@code account_currency_immutable}/generic-fallback
 * {@link DataIntegrityViolationException} branches and the {@link
 * OptimisticLockingFailureException} branch - {@code AccountControllerTest} only exercises the
 * {@code account_type_immutable} and {@code account_currency_immutable} branches end-to-end (a
 * genuine concurrent-write race and an arbitrary other constraint violation aren't practical to
 * force deterministically over HTTP). {@code custom_asset_valuation_currency_mismatch} (V26) is
 * this branch's other case: {@code CustomAssetValuationControllerTest} exercises the trigger
 * itself, via direct JDBC (since {@code CustomAssetValuationService} derives currency itself, no
 * request the service builds can trigger it any more) - but not this translation, so this unit test
 * is that branch's only coverage of the 409/FR-ACC-002 mapping.
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void accountTypeImmutableViolationIsTranslatedToAConflictNamingFrAcc005() {
    ProblemDetail problem =
        handler.handleDataIntegrityViolation(
            violationWithRootMessage(
                "ERROR: account_type_immutable: account 1 account_type cannot change from CASH to"
                    + " SAVINGS (FR-ACC-005/G5)"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).contains("FR-ACC-005/G5");
  }

  @Test
  void accountCurrencyImmutableViolationIsTranslatedToAConflictNamingFrAcc002() {
    ProblemDetail problem =
        handler.handleDataIntegrityViolation(
            violationWithRootMessage(
                "ERROR: account_currency_immutable: account 1 native_currency cannot change from"
                    + " CHF to EUR (FR-ACC-002)"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).contains("FR-ACC-002");
  }

  @Test
  void aRacingDuplicateExternalIdIsTranslatedToAConflictTellingTheCallerToRetry() {
    ProblemDetail problem =
        handler.handleDataIntegrityViolation(
            violationWithRootMessage(
                "ERROR: duplicate key value violates unique constraint"
                    + " \"uq_transaction_external_id\""));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).contains("externalId").contains("Retry");
  }

  @Test
  void customAssetValuationCurrencyMismatchIsTranslatedToAConflictNamingFrAcc002() {
    ProblemDetail problem =
        handler.handleDataIntegrityViolation(
            violationWithRootMessage(
                "ERROR: custom_asset_valuation_currency_mismatch: account 1 native_currency is CHF"
                    + " but a valuation in EUR was attempted (FR-ACC-002)"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).contains("FR-ACC-002");
  }

  @Test
  void aRacingDuplicateSnapshotIsTranslatedToAConflictTellingTheCallerToRetry() {
    ProblemDetail problem =
        handler.handleDataIntegrityViolation(
            violationWithRootMessage(
                "ERROR: duplicate key value violates unique constraint"
                    + " \"account_snapshot_account_id_snapshot_date_source_key\""));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).contains("already exists");
  }

  @Test
  void aSnapshotCurrencyMismatchIsTranslatedToAConflictNamingFrAcc002() {
    ProblemDetail problem =
        handler.handleDataIntegrityViolation(
            violationWithRootMessage(
                "ERROR: account_snapshot_currency_mismatch: account 1 native_currency is CHF but a"
                    + " snapshot in EUR was attempted (FR-ACC-002)"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).contains("FR-ACC-002");
  }

  @Test
  void anExistingResourceConflictNamesTheExistingId() {
    UUID existing = UUID.randomUUID();

    ProblemDetail problem =
        handler.handleExistingResourceConflict(
            new ExistingResourceConflictException(
                "Already there.", "existingSnapshotId", existing));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).isEqualTo("Already there.");
    assertThat(problem.getProperties()).containsEntry("existingSnapshotId", existing);
  }

  @Test
  void anUnrecognizedIntegrityViolationStillGetsACleanConflictNotTheRawDbMessage() {
    ProblemDetail problem =
        handler.handleDataIntegrityViolation(
            violationWithRootMessage(
                "ERROR: duplicate key value violates unique constraint \"some_internal_index\""));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).doesNotContain("some_internal_index");
  }

  @Test
  void anOptimisticLockingFailureIsTranslatedToAConflictNotA500() {
    ProblemDetail problem =
        handler.handleOptimisticLockingFailure(
            new OptimisticLockingFailureException("Row was updated or deleted by another"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).contains("changed by another request");
  }

  private DataIntegrityViolationException violationWithRootMessage(String rootMessage) {
    return new DataIntegrityViolationException(
        "could not execute statement", new RuntimeException(rootMessage));
  }
}
