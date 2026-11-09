import { ApiError } from '@/api/client';
import {
  canCommit,
  canToggleRow,
  filterCount,
  importErrorMessageKey,
  isKnownBatchStatus,
  isKnownRowStatus,
  isOpenBatch,
  isRetryableUploadError,
  isUnsupportedHoldings,
  MAX_FILE_BYTES,
  needsAccountConfirmation,
  retryAllowedAt,
  reviewableBatch,
  statementFileError,
  statementPeriod,
  statusCountsChanged,
  type Batch,
  type ImportRow,
} from './model';

const batch = (changes: Partial<Batch> = {}): Batch => ({
  id: 'batch',
  accountId: 'account',
  status: 'PARSED',
  sourceKind: 'CSV',
  sourceFileName: 'dkb.csv',
  templateId: 'dkb',
  templateVersion: '1',
  counts: {
    total: 48,
    newRows: 41,
    duplicates: 5,
    errors: 2,
    skipped: 0,
    warnings: 0,
    included: 41,
    imported: 0,
  },
  sameFileImportedIn: null,
  templateCandidates: [],
  uploadedAt: '2026-11-09T12:00:00Z',
  parsedAt: '2026-11-09T12:01:00Z',
  committedAt: null,
  rolledBackAt: null,
  rollbackReason: null,
  accountIdentification: null,
  statementBalance: null,
  holdings: null,
  version: 4,
  ...changes,
});
const row = (status: string, included: boolean): ImportRow => ({
  rowNumber: 1,
  status,
  included,
  rawData: {},
  canonical: null,
  duplicateOfTransactionId: null,
  duplicateOf: null,
  error: null,
  warnings: [],
  resultingTransactionId: null,
  recheck: null,
});

describe('statement import acceptance rules (#492)', () => {
  it('rejects empty, too-large and unsupported files before upload', () => {
    expect(statementFileError({ name: 'bank.csv', uri: 'file://bank', size: 0 })).toBe('empty');
    expect(
      statementFileError({ name: 'bank.pdf', uri: 'file://bank', size: MAX_FILE_BYTES + 1 }),
    ).toBe('tooLarge');
    expect(statementFileError({ name: 'bank.exe', uri: 'file://bank', size: 10 })).toBe(
      'unsupported',
    );
    for (const extension of ['csv', 'txt', 'pdf', 'xlsx', 'xls', 'xml', 'zip']) {
      expect(
        statementFileError({ name: 'bank.' + extension, uri: 'file://bank', size: 10 }),
      ).toBeNull();
    }
  });

  it('uses server counts, including 41 new, 5 duplicates and 2 errors', () => {
    const current = batch();
    expect(filterCount(current, 'ALL')).toBe(48);
    expect(filterCount(current, 'PARSED')).toBe(41);
    expect(filterCount(current, 'DUPLICATE')).toBe(5);
    expect(filterCount(current, 'ERROR')).toBe(2);
    expect(canCommit(current, false, true)).toBe(true);
    const afterToggle = batch({ counts: { ...current.counts, included: 42 } });
    expect(afterToggle.counts.included).toBe(42);
  });

  it('keeps an identical second file uncommittable when all rows are duplicate and off', () => {
    const same = batch({
      counts: { ...batch().counts, newRows: 0, duplicates: 48, included: 0 },
      sameFileImportedIn: { batchId: 'previous', committedAt: '2026-11-08T10:00:00Z' },
    });
    expect(canCommit(same, false, true)).toBe(false);
    expect(canToggleRow(row('DUPLICATE', false))).toBe(true);
    expect(canToggleRow(row('ERROR', false))).toBe(false);
    expect(canToggleRow(row('SKIPPED', false))).toBe(false);
  });

  it('blocks commits until account confirmation and whenever offline or busy', () => {
    const unconfirmed = batch({
      accountIdentification: {
        outcome: 'NO_MATCH',
        proposedAccountId: null,
        confirmationRequired: true,
        confirmed: false,
      },
    });
    expect(needsAccountConfirmation(unconfirmed)).toBe(true);
    expect(canCommit(unconfirmed, false, true)).toBe(false);
    expect(canCommit(batch(), true, true)).toBe(false);
    expect(canCommit(batch(), false, false)).toBe(false);
    expect(canCommit(batch({ status: 'UPLOADED' }), false, true)).toBe(false);
    expect(canCommit(batch({ status: 'COMMITTED' }), false, true)).toBe(false);
  });

  it('does not preview holdings or unknown batch states', () => {
    expect(reviewableBatch(null)).toBe(false);
    expect(isOpenBatch(batch({ status: 'UPLOADED' }))).toBe(true);
    expect(isOpenBatch(batch({ status: 'PARSED' }))).toBe(true);
    expect(isOpenBatch(batch({ status: 'COMMITTED' }))).toBe(false);
    expect(isKnownBatchStatus('FUTURE_STATUS')).toBe(false);
    expect(isKnownRowStatus('FUTURE_STATUS')).toBe(false);
    const holdings = batch({ holdings: { snapshot: 'NEW' } as Batch['holdings'] });
    expect(isUnsupportedHoldings(holdings)).toBe(true);
    expect(reviewableBatch(holdings)).toBe(false);
  });

  it('maps only documented error codes and allows retry for busy parsers', () => {
    const problem = (code: string) =>
      new ApiError('unsafe raw text', 400, {
        status: 400,
        title: 'unsafe raw title',
        code,
      });
    expect(importErrorMessageKey(problem('IMPORT_FILE_MALFORMED'))).toBe(
      'statement.error.IMPORT_FILE_MALFORMED',
    );
    expect(importErrorMessageKey(problem('UNKNOWN'))).toBeNull();
    expect(isRetryableUploadError(problem('IMPORT_PDF_BUSY'))).toBe(true);
    expect(isRetryableUploadError(problem('IMPORT_OCR_UNAVAILABLE'))).toBe(true);
    expect(isRetryableUploadError(problem('IMPORT_FILE_MALFORMED'))).toBe(false);
  });
});

describe('statement import helpers (#492 review fixes)', () => {
  const busy = (retryAfterSeconds?: number) =>
    new ApiError(
      'busy',
      503,
      { code: 'IMPORT_PDF_BUSY', status: 503, title: 'busy' },
      retryAfterSeconds,
    );

  it('allows a busy parser to be asked again only after its Retry-After', () => {
    expect(retryAllowedAt(busy(3), 1_000)).toBe(4_000);
    // Whole seconds: a fraction never offers Retry early.
    expect(retryAllowedAt(busy(0.2), 1_000)).toBe(2_000);
    expect(retryAllowedAt(busy(), 1_000)).toBeNull();
    expect(retryAllowedAt(busy(0), 1_000)).toBeNull();
    const malformed = new ApiError(
      'bad',
      400,
      {
        code: 'IMPORT_FILE_MALFORMED',
        status: 400,
        title: 'bad',
      },
      30,
    );
    expect(retryAllowedAt(malformed, 1_000)).toBeNull();
  });

  it('sees a re-check in the status counts, not in the included count', () => {
    const before = batch();
    expect(statusCountsChanged(before, batch({ counts: { ...before.counts, included: 42 } }))).toBe(
      false,
    );
    expect(
      statusCountsChanged(
        before,
        batch({ counts: { ...before.counts, newRows: 40, duplicates: 6 } }),
      ),
    ).toBe(true);
  });

  it('reads the statement period from the opening and closing balance dates', () => {
    const balance: NonNullable<Batch['statementBalance']> = {
      outcome: 'FOUND',
      date: '2026-10-31',
      balance: '1.00',
      currency: 'EUR',
      openingDate: '2026-10-01',
      openingBalance: '0.00',
      snapshot: 'NEW',
      existingSnapshot: null,
      adds: 'BALANCE',
      conflicts: [],
      explanation: null,
    };
    expect(statementPeriod(batch({ statementBalance: balance }))).toEqual({
      from: '2026-10-01',
      to: '2026-10-31',
    });
    expect(
      statementPeriod(batch({ statementBalance: { ...balance, openingDate: null } })),
    ).toBeNull();
    expect(statementPeriod(batch())).toBeNull();
  });
});

describe('row statuses of the import API (#492 web check)', () => {
  it('knows the statuses the server sends: PARSED is a new row with a switch', () => {
    for (const status of ['PARSED', 'DUPLICATE', 'ERROR', 'SKIPPED']) {
      expect(isKnownRowStatus(status)).toBe(true);
    }
    // The rows endpoint rejects status=NEW with a 400 and never answers it.
    expect(isKnownRowStatus('NEW')).toBe(false);
    expect(canToggleRow({ status: 'PARSED' } as ImportRow)).toBe(true);
    expect(filterCount(batch(), 'PARSED')).toBe(batch().counts.newRows);
  });
});
