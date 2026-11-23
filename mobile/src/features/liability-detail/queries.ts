import { useEffect, useState } from 'react';
import { type InfiniteData, useInfiniteQuery, useQuery } from '@tanstack/react-query';

import { ApiError } from '@/api/client';
import { queryKeys } from '@/api/queries/keys';
import type { components } from '@/api/schema';
import { typedApi } from '@/api/typed-api';

export type ScheduleEntry = components['schemas']['AmortisationScheduleEntryResponse'];
export type Repayment = components['schemas']['LoanRepaymentResponse'];

function validPage<T extends { content: unknown[]; page: { number: number; totalPages: number } }>(
  response: T,
  requested: number,
) {
  if (
    !Array.isArray(response?.content) ||
    response.content.some((row) => !row || typeof row !== 'object') ||
    response.page?.number !== requested ||
    !Number.isInteger(response.page.totalPages) ||
    response.page.totalPages < 0
  ) {
    throw new Error('Invalid liability collection response');
  }
  return response;
}

type SchedulePage = { content: ScheduleEntry[]; page: { number: number; totalPages: number } };

function isComplete(pages: readonly SchedulePage[] | undefined) {
  const last = pages?.at(-1)?.page;
  return !!last && last.number + 1 >= last.totalPages;
}

/**
 * Loads every schedule page. A refresh restarts the list at page 0, so the last complete schedule
 * stays on screen until the reload has all its pages again (also while the reload is offline).
 */
export function useLoanSchedule(accountId: string) {
  const query = useInfiniteQuery({
    queryKey: queryKeys.amortisationSchedule(accountId),
    initialPageParam: 0,
    queryFn: async ({ pageParam, signal }) =>
      validPage(
        await typedApi.get('/api/v1/accounts/{accountId}/amortisation-schedule', {
          params: { accountId },
          query: { page: pageParam, size: 200, sort: ['dueDate,asc'] },
          signal,
        }),
        pageParam,
      ),
    getNextPageParam: ({ page }) =>
      page.number + 1 < page.totalPages ? page.number + 1 : undefined,
  });
  const { data, fetchNextPage, hasNextPage, fetchStatus, isError } = query;
  useEffect(() => {
    if (data && hasNextPage && fetchStatus === 'idle' && !isError) {
      void fetchNextPage({ cancelRefetch: false });
    }
  }, [data, fetchNextPage, hasNextPage, fetchStatus, isError]);

  const [kept, setKept] = useState<{
    accountId: string;
    data: InfiniteData<SchedulePage>;
  } | null>(null);
  if (data && isComplete(data.pages) && kept?.data !== data) setKept({ accountId, data });
  const refilling =
    !isComplete(data?.pages) && !isError && kept?.accountId === accountId && query.isSuccess;
  const pages = refilling ? kept.data.pages : data?.pages;
  return { query, pages, complete: isComplete(pages) };
}

async function repayments(accountId: string, page: number, size: number, signal: AbortSignal) {
  return validPage(
    await typedApi.get('/api/v1/accounts/{accountId}/repayments', {
      params: { accountId },
      query: { page, size, sort: ['bookingDate,desc'] },
      signal,
    }),
    page,
  );
}

export function useRecentRepayments(accountId: string) {
  return useQuery({
    queryKey: queryKeys.loanRepayments(accountId, 3),
    queryFn: ({ signal }) => repayments(accountId, 0, 3, signal),
  });
}

export function useRepaymentHistory(accountId: string, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: queryKeys.loanRepayments(accountId, 50),
    enabled,
    initialPageParam: 0,
    queryFn: ({ pageParam, signal }) => repayments(accountId, pageParam, 50, signal),
    getNextPageParam: ({ page }) =>
      page.number + 1 < page.totalPages ? page.number + 1 : undefined,
  });
}

export function useCardStatement(cardAccountId: string) {
  return useQuery({
    queryKey: queryKeys.cardStatement(cardAccountId),
    queryFn: async ({ signal }) => {
      const options = { params: { cardAccountId }, signal };
      try {
        const statement = await typedApi.get('/api/v1/accounts/{cardAccountId}/statement', options);
        if (
          typeof statement?.cardAccountId !== 'string' ||
          statement.cardAccountId.toLowerCase() !== cardAccountId.toLowerCase() ||
          typeof statement.paid !== 'boolean'
        ) {
          throw new Error('Invalid card statement response');
        }
        return statement;
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 422) throw error;
        // The current statement uses a generic 422 when either cycle setting is missing.
        const config = await typedApi.get(
          '/api/v1/accounts/{cardAccountId}/statement-config',
          options,
        );
        if (config?.statementDay === null || config?.dueDateOffsetDays === null) return null;
        throw error;
      }
    },
  });
}
