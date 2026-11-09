import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
  type InfiniteData,
  type QueryClient,
} from '@tanstack/react-query';

import { ifMatch } from '@/api/queries/conflict';
import { invalidateFinancialData } from '@/api/queries/invalidation';
import { queryKeys } from '@/api/queries/keys';
import { typedApi } from '@/api/typed-api';

import {
  statusCountsChanged,
  type Batch,
  type ImportRow,
  type RowFilter,
  type StatementFile,
} from './model';

type RowsPage = { content: ImportRow[] };

export const importKeys = {
  batches: (accountId: string) => ['accounts', accountId, 'imports', 'open'] as const,
  batch: (accountId: string, batchId: string) => queryKeys.importBatch(accountId, batchId),
  /** Every status filter's row list of one batch. */
  allRows: (accountId: string, batchId: string) =>
    queryKeys.importRows(accountId, batchId).slice(0, 5),
  rows: (accountId: string, batchId: string, status: RowFilter) =>
    queryKeys.importRows(accountId, batchId, status),
  template: (templateId: string) => ['import-templates', templateId] as const,
};

/** The page size the spec fixes for the review (S7 "Large file"). */
export const ROWS_PAGE_SIZE = 50;

export function useOpenBatches(accountId: string, enabled = false) {
  return useQuery({
    queryKey: importKeys.batches(accountId),
    enabled: !!accountId && enabled,
    queryFn: () =>
      typedApi.get('/api/v1/accounts/{accountId}/imports', {
        params: { accountId },
        query: { page: 0, size: 50, sort: ['uploadedAt,desc'] },
      }),
  });
}

function fetchBatch(accountId: string, batchId: string): Promise<Batch> {
  return typedApi.get('/api/v1/accounts/{accountId}/imports/{batchId}', {
    params: { accountId, batchId },
  });
}

export function useImportBatch(accountId: string, batchId: string) {
  return useQuery({
    queryKey: importKeys.batch(accountId, batchId),
    enabled: !!accountId && !!batchId,
    queryFn: () => fetchBatch(accountId, batchId),
  });
}

export function useImportRows(accountId: string, batchId: string, status: RowFilter) {
  return useInfiniteQuery({
    queryKey: importKeys.rows(accountId, batchId, status),
    enabled: !!accountId && !!batchId,
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      typedApi.get('/api/v1/accounts/{accountId}/imports/{batchId}/rows', {
        params: { accountId, batchId },
        query: {
          ...(status === 'ALL' ? {} : { status }),
          page: pageParam,
          size: ROWS_PAGE_SIZE,
          sort: ['rowNumber,asc'],
        },
      }),
    getNextPageParam: (last) =>
      last.page.number + 1 < last.page.totalPages ? last.page.number + 1 : undefined,
  });
}

/** "Read as {templateName}": the batch names its template by id only. */
export function useImportTemplate(templateId: string | null | undefined) {
  return useQuery({
    queryKey: importKeys.template(templateId ?? ''),
    enabled: !!templateId,
    // A template version never changes its name; a new version has a new id.
    staleTime: Infinity,
    queryFn: () =>
      typedApi.get('/api/v1/import-templates/{id}', { params: { id: templateId ?? '' } }),
  });
}

/**
 * Builds the multipart body: the web picker hands over a browser File; React Native's FormData
 * takes a `{ uri, name, type }` part instead of a Blob.
 */
export function statementForm(file: StatementFile): FormData {
  const form = new FormData();
  if (file.webFile) {
    form.append('file', file.webFile, file.name);
  } else {
    form.append('file', {
      uri: file.uri,
      name: file.name,
      type: file.mimeType ?? 'application/octet-stream',
    } as unknown as Blob);
  }
  return form;
}

export function uploadStatement(
  accountId: string,
  file: StatementFile,
  signal?: AbortSignal,
): Promise<Batch> {
  return typedApi.upload('/api/v1/accounts/{accountId}/imports', {
    params: { accountId },
    body: statementForm(file),
    signal,
  });
}

export function parseStatement(accountId: string, batch: Batch, templateId: string) {
  return typedApi.post('/api/v1/accounts/{accountId}/imports/{batchId}/parse', {
    params: { accountId, batchId: batch.id },
    version: ifMatch(batch),
    body: { templateId },
  });
}
export function confirmStatementAccount(accountId: string, batch: Batch) {
  return typedApi.post('/api/v1/accounts/{accountId}/imports/{batchId}/confirm-account', {
    params: { accountId, batchId: batch.id },
    version: ifMatch(batch),
  });
}
export function setStatementRowIncluded(
  accountId: string,
  batch: Batch,
  rowNumber: number,
  included: boolean,
) {
  return typedApi.patch('/api/v1/accounts/{accountId}/imports/{batchId}/rows/{rowNumber}', {
    params: { accountId, batchId: batch.id, rowNumber },
    version: ifMatch(batch),
    body: { included },
  });
}
export function discardStatement(accountId: string, batch: Batch) {
  return typedApi.post('/api/v1/accounts/{accountId}/imports/{batchId}/discard', {
    params: { accountId, batchId: batch.id },
    version: ifMatch(batch),
  });
}
export function commitStatement(accountId: string, batch: Batch) {
  return typedApi.post('/api/v1/accounts/{accountId}/imports/{batchId}/commit', {
    params: { accountId, batchId: batch.id },
    version: ifMatch(batch),
  });
}
export function downloadErrorsCsv(accountId: string, batchId: string): Promise<string> {
  return typedApi.text('/api/v1/accounts/{accountId}/imports/{batchId}/errors.csv', {
    params: { accountId, batchId },
  });
}

/**
 * docs/api/conventions.md "Offset paging and refresh after imports": a change of the matching
 * result set discards the loaded pages and reloads from page 0.
 */
function restartAtFirstPage(client: QueryClient, queryKey: readonly unknown[]): void {
  for (const query of client.getQueryCache().findAll({ queryKey })) {
    client.setQueryData<InfiniteData<RowsPage>>(query.queryKey, (data) =>
      data && data.pages.length > 1
        ? { pages: data.pages.slice(0, 1), pageParams: data.pageParams.slice(0, 1) }
        : data,
    );
  }
}

/**
 * The batch's cache entry is the one authoritative copy of the batch and its version: every write
 * answers the whole batch, which replaces it, so the next If-Match always carries the newest
 * version without another read.
 */
export function useImportCache(accountId: string) {
  const client = useQueryClient();
  return {
    /** A write's response: the batch as the server now has it. */
    store(batch: Batch): void {
      client.setQueryData(importKeys.batch(accountId, batch.id), batch);
    },

    /**
     * After a write that answered the batch: it replaces the cached batch, and the rows follow.
     * A switched row shows its new value at once. The server may also have re-checked other rows
     * (US-07-17, #308): when a status count moved, what the status filters list changed, so the
     * lists restart at page 0 (docs/api/conventions.md); otherwise the loaded pages are refetched
     * in place and the member keeps their place in a long statement. The refetch is not awaited:
     * the next write may start at once, and it cancels a refetch still running.
     */
    async afterWrite(
      previous: Batch,
      next: Batch,
      switched?: { rowNumber: number; included: boolean },
    ): Promise<void> {
      const rowsKey = importKeys.allRows(accountId, next.id);
      client.setQueryData(importKeys.batch(accountId, next.id), next);
      await client.cancelQueries({ queryKey: rowsKey });
      if (switched) {
        client.setQueriesData<InfiniteData<RowsPage>>({ queryKey: rowsKey }, (data) =>
          data
            ? {
                ...data,
                pages: data.pages.map((page) => ({
                  ...page,
                  content: page.content.map((row) =>
                    row.rowNumber === switched.rowNumber
                      ? { ...row, included: switched.included }
                      : row,
                  ),
                })),
              }
            : data,
        );
      }
      if (statusCountsChanged(previous, next)) restartAtFirstPage(client, rowsKey);
      void client.invalidateQueries({ queryKey: rowsKey });
    },

    /** Another writer changed the batch (412, a re-check): reload it and its rows from page 0. */
    async reload(batchId: string): Promise<Batch> {
      const rowsKey = importKeys.allRows(accountId, batchId);
      const batch = await client.fetchQuery({
        queryKey: importKeys.batch(accountId, batchId),
        queryFn: () => fetchBatch(accountId, batchId),
        staleTime: 0,
      });
      restartAtFirstPage(client, rowsKey);
      await client.invalidateQueries({ queryKey: rowsKey });
      return batch;
    },

    /** A discarded batch leaves the account's open batches. */
    async discarded(): Promise<void> {
      await client.invalidateQueries({ queryKey: importKeys.batches(accountId) });
    },

    /** S7 step 3: S1, S2 and S3 reload their figures, and the open-batch list is outdated. */
    async committed(): Promise<void> {
      await invalidateFinancialData(client);
      await client.invalidateQueries({ queryKey: importKeys.batches(accountId) });
    },
  };
}
