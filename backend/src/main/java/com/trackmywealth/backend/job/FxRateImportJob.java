package com.trackmywealth.backend.job;

import com.trackmywealth.backend.service.FxRateImportService;
import com.trackmywealth.backend.service.TransferRecheckService;
import org.quartz.DisallowConcurrentExecution;

/**
 * US-06-04 (#223): the FX import - one job for every trigger, so that {@link
 * DisallowConcurrentExecution} keeps all of its runs apart across the cluster (V90's clustered JDBC
 * job store): two runs never fetch the same range twice.
 *
 * <ul>
 *   <li>{@link #IMPORT} (every two hours, and once at start) - every rate published since the last
 *       stored day, then any history an older booking now needs.
 *   <li>{@link #HISTORY_CHECK} (every few minutes) - only that history; without an older booking it
 *       makes no provider call.
 * </ul>
 *
 * <p>Either then derives the cross rates of currencies newly in use, and, when it stored rates,
 * re-runs the transfer detection that was waiting for them.
 */
@DisallowConcurrentExecution
public class FxRateImportJob extends CorrelatedJob {

  /** The trigger's job data key that selects what a run does. */
  public static final String MODE = "mode";

  public static final String IMPORT = "import";
  public static final String HISTORY_CHECK = "history-check";

  private final FxRateImportService fxRateImportService;
  private final TransferRecheckService transferRecheckService;
  private String mode = IMPORT;

  public FxRateImportJob(
      FxRateImportService fxRateImportService, TransferRecheckService transferRecheckService) {
    this.fxRateImportService = fxRateImportService;
    this.transferRecheckService = transferRecheckService;
  }

  /** Set by {@code QuartzJobBean} from the trigger's job data ({@link #MODE}). */
  public void setMode(String mode) {
    this.mode = mode;
  }

  @Override
  protected void run() {
    int stored = IMPORT.equals(mode) ? fxRateImportService.importLatest() : 0;
    stored += fxRateImportService.backfillHistory();
    fxRateImportService.deriveCrossRatesForNewCurrencies();
    if (stored > 0) {
      transferRecheckService.recheckPending();
    }
  }
}
