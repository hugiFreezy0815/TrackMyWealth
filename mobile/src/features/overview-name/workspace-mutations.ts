import { useMutation, useQueryClient } from '@tanstack/react-query';

import { typedApi } from '@/api/typed-api';
import { ifMatch } from '@/api/queries/conflict';
import { invalidateFinancialData } from '@/api/queries/invalidation';
import { queryKeys } from '@/api/queries/keys';

import type { Workspace } from './model';

export type WorkspaceChange = { workspace: Workspace; name: string | null };

/**
 * The backend deliberately accepts a JSON null to clear the name (#392).
 * Its OpenAPI request body currently describes a non-null string. The one
 * assertion below bridges that known contract gap without changing the
 * generated API files or weakening the typed request method.
 */
export function useUpdateOverviewName() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ workspace, name }: WorkspaceChange) =>
      typedApi.put('/api/v1/workspace/name', {
        version: ifMatch(workspace),
        body: { name: name as string },
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
