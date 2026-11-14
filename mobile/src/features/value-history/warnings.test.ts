import de from '@/i18n/de.json';
import en from '@/i18n/en.json';
import { changeIsUncertain, historyWarningMessageIds, type HistoryWarningCode } from './warnings';

// Every code the contract lists (api/openapi.yaml ValueHistoryResponse.warningCodes).
const ALL_CODES: HistoryWarningCode[] = [
  'VALUE_UNKNOWN',
  'INCEPTION_UNCERTAIN',
  'LIFECYCLE_UNVERIFIED',
  'ATTRIBUTION_PENDING_REVIEW',
  'ATTRIBUTION_FX_UNAVAILABLE',
  'CAPITAL_MOVEMENTS_UNAVAILABLE',
  'TRANSACTIONS_BEFORE_OPENING_BALANCE',
  'BOOKED_BEFORE_OPENING_BALANCE',
  'OPEN_RECONCILIATION_DIFFERENCE',
];

describe('history warning codes', () => {
  it.each(ALL_CODES)('%s has its own message in both catalogues', (code) => {
    const [id] = historyWarningMessageIds([code]);
    expect(id).not.toBe('history.warning.other');
    expect(en).toHaveProperty([id]);
    expect(de).toHaveProperty([id]);
  });

  it('maps each code to its message', () => {
    expect(
      historyWarningMessageIds([
        'VALUE_UNKNOWN',
        'ATTRIBUTION_FX_UNAVAILABLE',
        'ATTRIBUTION_PENDING_REVIEW',
        'CAPITAL_MOVEMENTS_UNAVAILABLE',
        'OPEN_RECONCILIATION_DIFFERENCE',
      ]),
    ).toEqual([
      'history.warning.unknownValue',
      'history.warning.missingFx',
      'history.warning.pendingMovements',
      'history.warning.capitalMovements',
      'overview.dataWarning.OPEN_RECONCILIATION_DIFFERENCE',
    ]);
  });

  it('shows one line for codes that share a message, and one generic line for unknown codes', () => {
    expect(
      historyWarningMessageIds([
        'INCEPTION_UNCERTAIN',
        'LIFECYCLE_UNVERIFIED',
        'A_FUTURE_CODE',
        'ANOTHER_FUTURE_CODE',
        'toString',
      ]),
    ).toEqual(['history.warning.lifecycle', 'history.warning.other']);
    expect(historyWarningMessageIds([])).toEqual([]);
  });

  it('treats only codes that can distort the change itself as uncertain', () => {
    expect(changeIsUncertain(['VALUE_UNKNOWN'])).toBe(true);
    expect(changeIsUncertain(['INCEPTION_UNCERTAIN'])).toBe(true);
    expect(changeIsUncertain(['LIFECYCLE_UNVERIFIED'])).toBe(true);
    expect(
      changeIsUncertain([
        'ATTRIBUTION_PENDING_REVIEW',
        'ATTRIBUTION_FX_UNAVAILABLE',
        'CAPITAL_MOVEMENTS_UNAVAILABLE',
        'OPEN_RECONCILIATION_DIFFERENCE',
        'A_FUTURE_CODE',
      ]),
    ).toBe(false);
    expect(changeIsUncertain([])).toBe(false);
  });
});
