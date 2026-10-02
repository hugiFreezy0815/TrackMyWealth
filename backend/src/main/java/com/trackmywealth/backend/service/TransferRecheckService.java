package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.PendingTransferDetection;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.repository.TransferDetectionFxPendingRepository;
import com.trackmywealth.backend.security.SystemWorkspaceContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #223: re-runs transfer detection where it could not judge a cross-currency pair because no FX
 * rate covered its booking date yet ({@code transfer_detection_fx_pending}, V54). Rates for such a
 * date are loaded in the background - an older transaction moves the history requirement (V52) -
 * and once the import has stored them, the FX import job calls {@link #recheckPending}, so the pair
 * is still proposed instead of staying unmatched until some later write nearby.
 *
 * <p>Each entry runs in its own transaction inside its workspace ({@link SystemWorkspaceContext}),
 * the entry removed in the same transaction: a run that fails leaves it for the next one, and one
 * workspace's failure does not stop the others. A date the rates still do not cover (a currency the
 * provider does not publish) is recorded again by the detection itself.
 */
@Service
public class TransferRecheckService {

  private static final Logger LOG = LoggerFactory.getLogger(TransferRecheckService.class);

  private final TransferDetectionFxPendingRepository pendingRepository;
  private final FxRateRepository fxRateRepository;
  private final TransferDetectionService transferDetectionService;
  private final TransactionTemplate transactionTemplate;
  private final String fxDefaultSource;

  public TransferRecheckService(
      TransferDetectionFxPendingRepository pendingRepository,
      FxRateRepository fxRateRepository,
      TransferDetectionService transferDetectionService,
      PlatformTransactionManager transactionManager,
      @Value("${app.fx.default-source}") String fxDefaultSource) {
    this.pendingRepository = pendingRepository;
    this.fxRateRepository = fxRateRepository;
    this.transferDetectionService = transferDetectionService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.fxDefaultSource = fxDefaultSource;
  }

  /**
   * Re-runs every recorded detection whose date the stored rates now reach.
   *
   * @return how many detections were re-run
   */
  public int recheckPending() {
    Optional<LocalDate> earliestRate = fxRateRepository.findEarliestRateDate(fxDefaultSource);
    if (earliestRate.isEmpty()) {
      return 0;
    }
    List<PendingTransferDetection> due = pendingRepository.findFrom(earliestRate.get());
    int rechecked = 0;
    for (PendingTransferDetection pending : due) {
      try {
        SystemWorkspaceContext.runInWorkspace(
            pending.workspaceId(),
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> {
                      pendingRepository.delete(pending.id());
                      transferDetectionService.detectAround(
                          pending.workspaceId(), pending.bookingDate());
                    }));
        rechecked++;
      } catch (RuntimeException e) {
        if (LOG.isWarnEnabled()) {
          LOG.warn(
              "Transfer re-detection for workspace {} on {} failed; kept for the next run: {}",
              pending.workspaceId(),
              pending.bookingDate(),
              e.getMessage(),
              e);
        }
      }
    }
    if (rechecked > 0) {
      LOG.info("Re-ran transfer detection on {} date(s) newly covered by FX rates", rechecked);
    }
    return rechecked;
  }
}
