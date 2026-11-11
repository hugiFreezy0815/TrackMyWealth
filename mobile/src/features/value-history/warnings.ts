import { DATA_WARNING_LABELS } from '@/features/overview/overview-model';

// The history endpoint mixes stable valuation codes with English messages that may contain
// account names. Classify the known messages without displaying any of the raw server text.
function warningMessageId(warning: string): string {
  const valuation = Object.entries(DATA_WARNING_LABELS).find(([code]) => warning === code);
  if (valuation) return valuation[1];

  switch (warning) {
    case 'Currency conversion unavailable for contributions.':
    case 'Currency conversion unavailable for portfolio capital flows.':
      return 'history.warning.missingFx';
    case 'Transactions are pending review; contribution attribution is incomplete.':
    case 'Transactions are pending review; portfolio attribution is incomplete.':
      return 'history.warning.pendingMovements';
    case 'Portfolio capital movement attribution unavailable.':
      return 'history.warning.capitalMovements';
    default:
      break;
  }

  if (
    /:(?: value unknown(?: at portfolio\/lifecycle change)?| opening value unavailable)$/.test(
      warning,
    )
  ) {
    return 'history.warning.unknownValue';
  }
  if (
    /:(?: inception before first activity is uncertain| historical lifecycle state unavailable| portfolio and archive history before tracking may have been restated)$/.test(
      warning,
    )
  ) {
    return 'history.warning.lifecycle';
  }
  return 'history.warning.other';
}

export function historyWarningMessageIds(warnings: readonly string[]): string[] {
  return Array.from(new Set(warnings.map(warningMessageId)));
}
