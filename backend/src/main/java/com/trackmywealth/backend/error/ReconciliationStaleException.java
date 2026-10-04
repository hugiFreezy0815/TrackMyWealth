package com.trackmywealth.backend.error;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

/**
 * 409 {@link ApiErrorCode#RECONCILIATION_STALE} (US-25-03): the reconciliation result no longer
 * describes the account's newest comparison. {@code status} and {@code differenceAmount} carry the
 * current figure, so a client can show it without another round trip.
 *
 * <p>A type of its own so a decision's transaction can commit on it ({@code noRollbackFor}): the
 * re-evaluation that revealed the staleness is the truth. Rolled back, the client's reload would
 * show the old figure and version again, and every retry would hit the same 409.
 */
public class ReconciliationStaleException extends ApiException {

  private static final long serialVersionUID = 1L;

  public ReconciliationStaleException(String detail, String status, BigDecimal differenceAmount) {
    super(HttpStatus.CONFLICT, ApiErrorCode.RECONCILIATION_STALE, detail);
    getBody().setProperty("status", status);
    getBody()
        .setProperty(
            "differenceAmount", differenceAmount == null ? null : differenceAmount.toPlainString());
  }
}
