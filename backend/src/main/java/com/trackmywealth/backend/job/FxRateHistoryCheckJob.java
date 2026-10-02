package com.trackmywealth.backend.job;

import com.trackmywealth.backend.service.FxRateImportService;
import org.quartz.DisallowConcurrentExecution;

/**
 * US-06-04 (#223): loads older FX history soon after a transaction older than every stored rate is
 * booked (an import of old statements), instead of waiting for the next daily import. Without such
 * a booking it makes no provider call.
 */
@DisallowConcurrentExecution
public class FxRateHistoryCheckJob extends CorrelatedJob {

  private final FxRateImportService fxRateImportService;

  public FxRateHistoryCheckJob(FxRateImportService fxRateImportService) {
    this.fxRateImportService = fxRateImportService;
  }

  @Override
  protected void run() {
    fxRateImportService.backfillHistory();
  }
}
