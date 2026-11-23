import { useInfiniteQuery, useQuery } from '@tanstack/react-query';

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

export function useLoanSchedule(accountId: string) {
  return useQuery({
    queryKey: queryKeys.amortisationSchedule(accountId),
    queryFn: async ({ signal }) => {
      const entries: ScheduleEntry[] = [];
      for (let page = 0; page < 3; page++) {
        const response = validPage(
          await typedApi.get('/api/v1/accounts/{accountId}/amortisation-schedule', {
            params: { accountId },
            query: { page, size: 200, sort: ['dueDate,asc'] },
            signal,
          }),
          page,
        );
        entries.push(...response.content);
        if (page + 1 >= response.page.totalPages) return { entries, complete: true };
      }
      return { entries, complete: false };
    },
  });
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
