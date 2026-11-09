import { ApiError } from '@/api/client';
import { problemOf } from '@/api/problems';
import type { components } from '@/api/schema';

export type Batch = components['schemas']['ImportBatchResponse'];
export type ImportRow = components['schemas']['ImportRowResponse'];
export type Account = components['schemas']['AccountSummaryResponse'];
export type ListedAccount = components['schemas']['AccountListItemResponse'];
/**
 * The row statuses of the contract (`ImportBatchService.ROW_STATUSES`): PARSED is a readable row
 * that is new to the ledger, shown as "New".
 */
export type RowFilter = 'ALL' | 'PARSED' | 'DUPLICATE' | 'ERROR' | 'SKIPPED';
export type StatementFile = {
  name: string;
  uri: string;
  size: number | null;
  mimeType?: string | null;
  /** Provided by the browser document picker or drop zone, never by native Expo. */
  webFile?: File;
};

export const MAX_FILE_BYTES = 5 * 1024 * 1024;
export const ACCEPTED_EXTENSIONS = ['csv', 'txt', 'pdf', 'xlsx', 'xls', 'xml', 'zip'] as const;
export const FILTERS: readonly RowFilter[] = ['ALL', 'PARSED', 'DUPLICATE', 'ERROR', 'SKIPPED'];
export const OPEN_STATUSES = ['UPLOADED', 'PARSED'] as const;
export const KNOWN_BATCH_STATUSES = [
  'UPLOADED',
  'PARSED',
  'COMMITTED',
  'DISCARDED',
  'ROLLED_BACK',
] as const;
export const KNOWN_ROW_STATUSES = ['PARSED', 'DUPLICATE', 'ERROR', 'SKIPPED'] as const;
export const RETRYABLE_UPLOAD_CODES = [
  'IMPORT_PDF_BUSY',
  'IMPORT_SPREADSHEET_BUSY',
  'IMPORT_OCR_UNAVAILABLE',
] as const;

/** Unsupported and ambiguous uploads must never reach the server. */
export function statementFileError(
  file: StatementFile,
): 'tooLarge' | 'unsupported' | 'empty' | null {
  if (file.size === 0) return 'empty';
  if (file.size !== null && file.size > MAX_FILE_BYTES) return 'tooLarge';
  const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
  return (ACCEPTED_EXTENSIONS as readonly string[]).includes(extension) ? null : 'unsupported';
}

export function isOpenBatch(batch: Batch): boolean {
  return (OPEN_STATUSES as readonly string[]).includes(batch.status);
}
export function reviewableBatch(batch: Batch | null | undefined): boolean {
  return batch?.status === 'PARSED' && batch.holdings == null;
}
export function canCommit(batch: Batch, busy: boolean, online: boolean): boolean {
  return (
    online &&
    !busy &&
    reviewableBatch(batch) &&
    batch.counts.included > 0 &&
    !(batch.accountIdentification?.confirmationRequired && !batch.accountIdentification.confirmed)
  );
}
/** A row's switch only for new (PARSED, default on) and DUPLICATE (default off) rows. */
export function canToggleRow(row: ImportRow): boolean {
  return row.status === 'PARSED' || row.status === 'DUPLICATE';
}
export function filterCount(batch: Batch, status: RowFilter): number {
  switch (status) {
    case 'ALL':
      return batch.counts.total;
    case 'PARSED':
      return batch.counts.newRows;
    case 'DUPLICATE':
      return batch.counts.duplicates;
    case 'ERROR':
      return batch.counts.errors;
    case 'SKIPPED':
      return batch.counts.skipped;
    default:
      return 0;
  }
}
export function needsAccountConfirmation(batch: Batch): boolean {
  return (
    batch.accountIdentification?.confirmationRequired === true &&
    batch.accountIdentification.confirmed === false
  );
}
export function isKnownBatchStatus(value: string): boolean {
  return (KNOWN_BATCH_STATUSES as readonly string[]).includes(value);
}
export function isKnownRowStatus(value: string): boolean {
  return (KNOWN_ROW_STATUSES as readonly string[]).includes(value);
}
export function importProblemCode(error: unknown): string | null {
  return error instanceof ApiError ? (problemOf(error)?.code ?? null) : null;
}
export function isRetryableUploadError(error: unknown): boolean {
  const code = importProblemCode(error);
  return code !== null && (RETRYABLE_UPLOAD_CODES as readonly string[]).includes(code);
}

/**
 * When a busy parser may be asked again (S7: `RETRY_LATER` honours `Retry-After`): an epoch
 * millisecond, or null when the server set no delay and Retry is offered at once.
 */
export function retryAllowedAt(error: unknown, now = Date.now()): number | null {
  if (!isRetryableUploadError(error) || !(error instanceof ApiError)) return null;
  const seconds = error.retryAfterSeconds;
  return typeof seconds === 'number' && seconds > 0 ? now + Math.ceil(seconds) * 1000 : null;
}

/**
 * Whether a write moved rows between statuses (a re-check, #308), i.e. changed what a status
 * filter lists; `included` alone only flips switches.
 */
export function statusCountsChanged(previous: Batch, next: Batch): boolean {
  const keys = ['total', 'newRows', 'duplicates', 'errors', 'skipped', 'warnings'] as const;
  return keys.some((key) => previous.counts[key] !== next.counts[key]);
}

/** The statement's period as the file states it (opening and closing balance dates), if read. */
export function statementPeriod(batch: Batch): { from: string; to: string } | null {
  const balance = batch.statementBalance;
  return balance?.openingDate && balance.date
    ? { from: balance.openingDate, to: balance.date }
    : null;
}

export const IMPORT_UPLOAD_ERRORS: readonly string[] = [
  'IMPORT_FILE_EMPTY',
  'IMPORT_FILE_NO_DATA_ROWS',
  'IMPORT_FILE_MALFORMED',
  'IMPORT_FILE_TOO_LARGE',
  'IMPORT_FILE_TOO_MANY_ROWS',
  'IMPORT_FILE_TOO_MANY_PAGES',
  'IMPORT_TEMPLATE_MISMATCH',
  'IMPORT_ENCODING_SUSPECT',
  'IMPORT_CAMT_UNSUPPORTED',
  'IMPORT_CAMT_LIMIT_EXCEEDED',
  'IMPORT_CAMT_MULTIPLE_ACCOUNTS',
  'IMPORT_PDF_NO_TEXT',
  'IMPORT_PDF_LIMIT_EXCEEDED',
  'IMPORT_SPREADSHEET_LIMIT_EXCEEDED',
  'IMPORT_OCR_FAILED',
  'IMPORT_PDF_BUSY',
  'IMPORT_SPREADSHEET_BUSY',
  'IMPORT_OCR_UNAVAILABLE',
  'IMPORT_TOO_MANY_OPEN_BATCHES',
  'IMPORT_BATCH_STATE',
  'IMPORT_ACCOUNT_UNCONFIRMED',
  'IMPORT_SNAPSHOT_CONFLICT',
  'IMPORT_SNAPSHOT_ALREADY_RECORDED',
  'IMPORT_SNAPSHOT_RECONCILIATION_DECIDED',
  'VERSION_CONFLICT',
] as const;

/** The response status counts, not client-side calculations, decide what gets imported. */
export function importErrorMessageKey(error: unknown): string | null {
  const code = importProblemCode(error);
  return code && IMPORT_UPLOAD_ERRORS.includes(code) ? 'statement.error.' + code : null;
}

/** A transaction statement only; the holdings flow is explicitly deferred to #367. */
export function isUnsupportedHoldings(batch: Batch): boolean {
  return batch.holdings !== null;
}
