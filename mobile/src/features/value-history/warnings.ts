import { labelFor } from '@/api/enum-label';
import type { components } from '@/api/schema';
import { DATA_WARNING_LABELS } from '@/features/overview/overview-model';

export type HistoryWarningCode =
  components['schemas']['ValueHistoryResponse']['warningCodes'][number];

/**
 * Message per stable history warning code (#496 review). The server's `warnings[]` sentences name
 * accounts and are for support only; the app never shows them.
 */
const HISTORY_WARNING_LABELS: Record<HistoryWarningCode, string> = {
  VALUE_UNKNOWN: 'history.warning.unknownValue',
  INCEPTION_UNCERTAIN: 'history.warning.inception',
  LIFECYCLE_UNVERIFIED: 'history.warning.lifecycle',
  ATTRIBUTION_PENDING_REVIEW: 'history.warning.pendingMovements',
  ATTRIBUTION_FX_UNAVAILABLE: 'history.warning.missingFx',
  CAPITAL_MOVEMENTS_UNAVAILABLE: 'history.warning.capitalMovements',
  ...DATA_WARNING_LABELS,
};
const OTHER_HISTORY_WARNING = 'history.warning.other';

/**
 * Codes that make the wealth change itself unreliable, not only its split: an account whose value
 * or existence is unknown at one end of the period is left out of that end's sum. They do not
 * touch the return (#607): the server measures it over tracked history only and sends null when it
 * cannot.
 */
const DISTORTING_CODES: readonly string[] = [
  'VALUE_UNKNOWN',
  'INCEPTION_UNCERTAIN',
  'LIFECYCLE_UNVERIFIED',
] satisfies readonly HistoryWarningCode[];

/** Message ids for the codes, de-duplicated; a code this app doesn't know yet reads generically. */
export function historyWarningMessageIds(codes: readonly string[]): string[] {
  return Array.from(
    new Set(codes.map((code) => labelFor(HISTORY_WARNING_LABELS, code, OTHER_HISTORY_WARNING))),
  );
}

export function changeIsUncertain(codes: readonly string[]): boolean {
  return codes.some((code) => DISTORTING_CODES.includes(code));
}
