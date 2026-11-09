import { useMutation, useQueryClient } from '@tanstack/react-query';

import { typedApi } from '@/api/typed-api';
import { ifMatch } from '@/api/queries/conflict';
import { invalidateFinancialData } from '@/api/queries/invalidation';
import { queryKeys } from '@/api/queries/keys';

import type { Workspace } from './model';

/** `name: null` clears the name; the first tab then shows "All portfolios". */
export type WorkspaceNameChange = { workspace: Workspace; name: string | null };

export function useUpdateOverviewName() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ workspace, name }: WorkspaceNameChange) =>
      typedApi.put('/api/v1/workspace/name', {
        version: ifMatch(workspace),
        body: { name },
      }),
    onSuccess: (updated) => {
      // Immediately visible in the first tab on every mounted overview.
      client.setQueryData(queryKeys.workspace, updated);
    },
  });
}

export function useUpdateOverviewCurrency() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ workspace, currency }: { workspace: Workspace; currency: string }) =>
      typedApi.put('/api/v1/workspace/currency', {
        version: ifMatch(workspace),
        body: { currency },
      }),
    onSuccess: (updated) => {
      client.setQueryData(queryKeys.workspace, updated);
      // The default-currency totals and all portfolios change their value basis.
      void invalidateFinancialData(client);
    },
  });
}
