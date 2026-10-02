package com.trackmywealth.backend.job;

import com.trackmywealth.backend.service.FxRateImportService;
import org.quartz.DisallowConcurrentExecution;

/**
 * US-06-04 (#223): the daily FX import - every rate published since the last stored day, then any
 * history an older booking now needs. Clustered (V90's JDBC job store), so it runs on one instance
 * per firing; {@link DisallowConcurrentExecution} keeps a slow backfill from overlapping the next
 * firing.
 */
@DisallowConcurrentExecution
public class FxRateDailyImportJob extends CorrelatedJob {

  private final FxRateImportService fxRateImportService;

  public FxRateDailyImportJob(FxRateImportService fxRateImportService) {
    this.fxRateImportService = fxRateImportService;
  }

  @Override
  protected void run() {
    fxRateImportService.importLatest();
    fxRateImportService.backfillHistory();
  }
}
