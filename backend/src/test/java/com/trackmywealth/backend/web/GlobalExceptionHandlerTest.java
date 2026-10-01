package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ExistingResourceConflictException;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.context.request.ServletWebRequest;

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
  void aRacingDuplicateCategoryCodeIsTranslatedToAConflictTellingTheCallerToRetry() {
    ProblemDetail problem =
        handler.handleDataIntegrityViolation(
            violationWithRootMessage(
                "ERROR: duplicate key value violates unique constraint"
                    + " \"uq_category_workspace_code\""));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).contains("category").contains("Retry");
  }

  @Test
  void aRacingFirstCategoryCustomisationIsTranslatedToARetryableConflict() {
    ProblemDetail problem =
        handler.handleDataIntegrityViolation(
            violationWithRootMessage(
                "ERROR: duplicate key value violates unique constraint "
                    + "uq_workspace_category_override"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).contains("category").contains("Retry");
    assertThat(problem.getProperties()).containsEntry(ApiErrorCode.PROPERTY, ApiErrorCode.RETRY);
    assertThat(problem.getDetail()).doesNotContain("uq_workspace_category_override");
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
  void anOptimisticLockingFailureUnderIfMatchIsTheSamePreconditionConflict() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.IF_MATCH, "\"3\"");

    ProblemDetail problem =
        handler.handleOptimisticLockingFailure(
            new OptimisticLockingFailureException("Row was updated or deleted by another"),
            new ServletWebRequest(request));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.PRECONDITION_FAILED.value());
    assertThat(problem.getProperties())
        .containsEntry(ApiErrorCode.PROPERTY, ApiErrorCode.VERSION_CONFLICT);
    assertThat(problem.getDetail()).contains("Reload");
  }

  // RFC 9110: 412 means a precondition the client sent failed; without If-Match there was none.
  @Test
  void anOptimisticLockingFailureWithoutIfMatchStaysAConflict() {
    ProblemDetail problem =
        handler.handleOptimisticLockingFailure(
            new OptimisticLockingFailureException("Row was updated or deleted by another"),
            new ServletWebRequest(new MockHttpServletRequest()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getProperties())
        .containsEntry(ApiErrorCode.PROPERTY, ApiErrorCode.VERSION_CONFLICT);
  }

  // --- EPIC-29 (#149): stable codes and no leaks ------------------------------------------------

  @Test
  void aConflictCarriesTheCodeAClientBranchesOn() {
    assertThat(
            handler
                .handleDataIntegrityViolation(
                    violationWithRootMessage(
                        "ERROR: duplicate key value violates unique constraint"
                            + " \"uq_transaction_external_id\""))
                .getProperties())
        .containsEntry(ApiErrorCode.PROPERTY, ApiErrorCode.RETRY);
    assertThat(
            handler
                .handleDataIntegrityViolation(
                    violationWithRootMessage("ERROR: account_type_immutable: ..."))
                .getProperties())
        .containsEntry(ApiErrorCode.PROPERTY, ApiErrorCode.IMMUTABLE_FIELD);
    assertThat(
            handler
                .handleOptimisticLockingFailure(
                    new ObjectOptimisticLockingFailureException(Object.class, "id"),
                    new ServletWebRequest(new MockHttpServletRequest()))
                .getProperties())
        .containsEntry(ApiErrorCode.PROPERTY, ApiErrorCode.VERSION_CONFLICT);
    assertThat(
            handler
                .handleDataIntegrityViolation(violationWithRootMessage("ERROR: something else"))
                .getProperties())
        .containsEntry(ApiErrorCode.PROPERTY, ApiErrorCode.CONFLICT);
  }

  @Test
  void anUnexpectedErrorSaysNothingAboutItsCause() {
    ProblemDetail problem =
        handler.handleUnexpected(
            new IllegalStateException("SELECT * FROM app_user WHERE password_hash = 'secret'"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    assertThat(problem.getProperties()).containsEntry(ApiErrorCode.PROPERTY, ApiErrorCode.INTERNAL);
    assertThat(problem.getDetail())
        .doesNotContain("SELECT")
        .doesNotContain("secret")
        .doesNotContain("IllegalStateException");
  }

  @Test
  void methodNotAllowedIsARequestErrorNotNotFound() {
    assertThat(ApiErrorCode.forStatus(HttpStatus.METHOD_NOT_ALLOWED))
        .isEqualTo(ApiErrorCode.VALIDATION_FAILED)
        .isNotEqualTo(ApiErrorCode.NOT_FOUND);
  }

  @Test
  void aCodedExceptionKeepsItsOwnCode() {
    ApiException archived =
        new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ACCOUNT_ARCHIVED, "Archived.");

    assertThat(ProblemDetails.decorate(archived.getBody()).getProperties())
        .containsEntry(ApiErrorCode.PROPERTY, ApiErrorCode.ACCOUNT_ARCHIVED);
    assertThat(archived.getBody().getDetail()).isEqualTo("Archived.");
  }

  private DataIntegrityViolationException violationWithRootMessage(String rootMessage) {
    return new DataIntegrityViolationException(
        "could not execute statement", new RuntimeException(rootMessage));
  }

  // #197: a client that went away mid-response is not answered with a 500 or logged as an error.
  // ClientDisconnectIntegrationTest shows the same through a real DispatcherServlet.
  @Test
  void aClientThatDisconnectedGetsNoErrorResponse() {
    assertThat(handler.handleUnexpected(new IOException("Broken pipe"))).isNull();
    assertThat(handler.handleUnexpected(new IllegalStateException("boom")).getStatus())
        .isEqualTo(500);
  }

  @Test
  void aClientThatDisconnectedWhileTheBodyWasWrittenGetsNoErrorResponse() {
    HttpMessageNotWritableException disconnected =
        new HttpMessageNotWritableException(
            "Could not write JSON", new IOException("Connection reset by peer"));

    assertThat(notWritable(disconnected)).isNull();
  }

  @Test
  void aBodyThatCannotBeWrittenForAnyOtherReasonIsStillA500() {
    HttpMessageNotWritableException broken =
        new HttpMessageNotWritableException(
            "Could not write JSON", new IllegalStateException("no serializer"));

    assertThat(notWritable(broken).getStatusCode().value()).isEqualTo(500);
  }

  private ResponseEntity<Object> notWritable(HttpMessageNotWritableException ex) {
    return handler.handleHttpMessageNotWritable(
        ex,
        new HttpHeaders(),
        HttpStatus.INTERNAL_SERVER_ERROR,
        new ServletWebRequest(new MockHttpServletRequest()));
  }
}
