/**
 * Test support only (never imported by the app): an in-memory stand-in for the import API behind
 * `typedApi`, so the S7 tests run the real query cache, paging and If-Match handling. Synthetic
 * data only (no real statement samples, #230).
 */
import { ApiError } from '@/api/client';

import type { Batch, ImportRow } from './model';

type Call = { method: string; path: string; options: Record<string, unknown> };
type Options = {
  params?: Record<string, string | number>;
  query?: Record<string, unknown>;
  body?: unknown;
  version?: number;
  signal?: AbortSignal;
};

export function problem(code: string, status: number, retryAfterSeconds?: number): ApiError {
  return new ApiError(
    `failed with ${status}`,
    status,
    { code, status, title: code, type: 'about:blank' },
    retryAfterSeconds,
  );
}

export function syntheticBatch(changes: Partial<Batch> = {}): Batch {
  return {
    id: 'b-1',
    accountId: 'a-1',
    status: 'PARSED',
    sourceKind: 'CSV',
    sourceFileName: 'synthetic-dkb.csv',
    templateId: 't-dkb',
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
  };
}

export function syntheticRow(
  rowNumber: number,
  status: string,
  changes: Partial<ImportRow> = {},
): ImportRow {
  return {
    rowNumber,
    status,
    included: status === 'PARSED',
    rawData: {},
    canonical: {
      bookingDate: '2026-11-08',
      valueDate: null,
      amount: '-12.50',
      currency: 'EUR',
      transactionType: 'EXPENSE',
      description: `Synthetic shop ${rowNumber}`,
      counterpartyName: null,
      externalId: null,
      mcc: null,
      iso20022BankTransactionCode: null,
      notes: null,
      originalAmount: null,
      originalCurrency: null,
      bankExchangeRate: null,
      valueDateAdjusted: false,
      groupReference: null,
      savingsPocket: false,
      savingsPocketOtherCurrency: false,
    },
    duplicateOfTransactionId: null,
    duplicateOf: null,
    error: null,
    warnings: [],
    resultingTransactionId: null,
    recheck: null,
    ...changes,
  };
}

/** Routes by the contract path template, as typedApi receives it. */
export class FakeImportServer {
  batch: Batch | null;
  rows: ImportRow[];
  open: Batch[] = [];
  /** What the next upload answers. */
  uploadAnswer: Batch;
  calls: Call[] = [];
  /** Re-check on the next PATCH: these rows change status (moves rows between filters). */
  recheckOnNextPatch: { rowNumber: number; status: string }[] = [];
  /** An upload that never answers until its signal aborts. */
  hangUploads = false;
  private failures: { method: string; path: string; error: unknown }[] = [];

  constructor(batch: Batch | null, rows: ImportRow[] = []) {
    this.batch = batch;
    this.rows = rows;
    this.uploadAnswer = syntheticBatch();
  }

  /** The next call of `method` on `path` fails with `error`. */
  failNext(method: string, path: string, error: unknown): void {
    this.failures.push({ method, path, error });
  }

  callsOf(method: string, path: string): Call[] {
    return this.calls.filter((call) => call.method === method && call.path === path);
  }

  private take(method: string, path: string, options: Options): void {
    this.calls.push({ method, path, options: options as Record<string, unknown> });
    const index = this.failures.findIndex((f) => f.method === method && f.path === path);
    if (index >= 0) {
      const [failure] = this.failures.splice(index, 1);
      throw failure.error;
    }
  }

  private current(): Batch {
    if (!this.batch) throw problem('NOT_FOUND', 404);
    return this.batch;
  }

  private write(options: Options, change: (batch: Batch) => Batch): Batch {
    const batch = this.current();
    if (options.version !== batch.version) throw problem('VERSION_CONFLICT', 412);
    this.batch = { ...change(batch), version: batch.version + 1 };
    return this.batch;
  }

  private readonly getRoutes: Record<string, (options: Options) => unknown> = {
    '/api/v1/accounts/{accountId}/imports': () => ({
      content: this.open,
      page: page(0, 50, this.open.length),
    }),
    '/api/v1/accounts/{accountId}/imports/{batchId}': () => this.current(),
    '/api/v1/accounts/{accountId}/imports/{batchId}/rows': (options) => {
      const status = options.query?.status as string | undefined;
      const number = Number(options.query?.page ?? 0);
      const size = Number(options.query?.size ?? 50);
      const matching = this.rows.filter((row) => !status || row.status === status);
      return {
        content: matching.slice(number * size, (number + 1) * size),
        page: page(number, size, matching.length),
      };
    },
    '/api/v1/import-templates/{id}': (options) => ({
      id: options.params?.id,
      name: 'DKB Girokonto (CSV)',
    }),
  };

  private readonly postRoutes: Record<string, (options: Options) => unknown> = {
    '/api/v1/accounts/{accountId}/imports/{batchId}/parse': (options) =>
      this.write(options, (batch) => ({
        ...batch,
        status: 'PARSED',
        templateId: (options.body as { templateId: string }).templateId,
        templateCandidates: [],
      })),
    '/api/v1/accounts/{accountId}/imports/{batchId}/confirm-account': (options) =>
      this.write(options, (batch) => ({
        ...batch,
        accountIdentification: batch.accountIdentification && {
          ...batch.accountIdentification,
          confirmed: true,
        },
      })),
    '/api/v1/accounts/{accountId}/imports/{batchId}/commit': (options) =>
      this.write(options, (batch) => ({
        ...batch,
        status: 'COMMITTED',
        counts: { ...batch.counts, imported: batch.counts.included },
      })),
    '/api/v1/accounts/{accountId}/imports/{batchId}/discard': (options) => {
      const id = options.params?.batchId;
      if (this.batch?.id === id) {
        return this.write(options, (batch) => ({ ...batch, status: 'DISCARDED' }));
      }
      const item = this.open.find((candidate) => candidate.id === id);
      if (!item || item.version !== options.version) throw problem('VERSION_CONFLICT', 412);
      this.open = this.open.filter((candidate) => candidate.id !== id);
      return { ...item, status: 'DISCARDED', version: item.version + 1 };
    },
  };

  private route(
    routes: Record<string, (options: Options) => unknown>,
    method: string,
    path: string,
    options: Options,
  ): unknown {
    this.take(method, path, options);
    const handler = routes[path];
    if (!handler) throw new Error(`Unexpected ${method.toUpperCase()} ${path}`);
    return handler(options);
  }

  get = async (path: string, options: Options = {}): Promise<unknown> =>
    this.route(this.getRoutes, 'get', path, options);

  post = async (path: string, options: Options = {}): Promise<unknown> =>
    this.route(this.postRoutes, 'post', path, options);

  patch = async (path: string, options: Options = {}): Promise<unknown> => {
    this.take('patch', path, options);
    if (path !== '/api/v1/accounts/{accountId}/imports/{batchId}/rows/{rowNumber}') {
      throw new Error(`Unexpected PATCH ${path}`);
    }
    const rowNumber = Number(options.params?.rowNumber);
    const included = (options.body as { included: boolean }).included;
    return this.write(options, (batch) => {
      const recheck = this.recheckOnNextPatch;
      this.recheckOnNextPatch = [];
      this.rows = this.rows.map((row) => {
        const moved = recheck.find((r) => r.rowNumber === row.rowNumber);
        const next = moved ? { ...row, status: moved.status, recheck: moved.status as 'NEW' } : row;
        return row.rowNumber === rowNumber ? { ...next, included } : next;
      });
      const count = (status: string) => this.rows.filter((row) => row.status === status).length;
      const changed = recheck.length > 0;
      return {
        ...batch,
        counts: {
          ...batch.counts,
          included: batch.counts.included + (included ? 1 : -1),
          ...(changed ? { newRows: count('PARSED'), duplicates: count('DUPLICATE') } : {}),
        },
      };
    });
  };

  upload = async (path: string, options: Options): Promise<unknown> => {
    this.take('upload', path, options);
    if (this.hangUploads) {
      return new Promise((_resolve, reject) =>
        options.signal?.addEventListener('abort', () =>
          reject(Object.assign(new Error('Aborted'), { name: 'AbortError' })),
        ),
      );
    }
    this.batch = this.uploadAnswer;
    return this.batch;
  };

  text = async (path: string, options: Options): Promise<string> => {
    this.take('text', path, options);
    return 'rowNumber,code,message\n7,IMPORT_ROW_DATE_INVALID,Invalid date\n';
  };
}

function page(number: number, size: number, totalElements: number) {
  return { number, size, totalElements, totalPages: Math.max(1, Math.ceil(totalElements / size)) };
}
