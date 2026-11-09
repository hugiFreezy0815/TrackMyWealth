import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';

import { queryKeys } from '@/api/queries/keys';
import type { components } from '@/api/schema';
import { typedApi } from '@/api/typed-api';
import en from '@/i18n/en.json';

import { OverviewNameSettings } from './settings-row';

jest.mock('@/global.css', () => ({}));
jest.mock('@/api/typed-api', () => ({ typedApi: { get: jest.fn(), put: jest.fn() } }));

type Workspace = components['schemas']['WorkspaceResponse'];
const unnamed: Workspace = { id: 'w-1', name: null, currency: 'CHF', version: 3 };

async function mounted(current: Workspace) {
  const client = new QueryClient({
    defaultOptions: {
      // gcTime: Infinity, or GC timers keep a single-file jest run alive after it passes.
      queries: { retry: false, gcTime: Infinity, staleTime: Infinity },
      mutations: { retry: false, gcTime: Infinity },
    },
  });
  client.setQueryData(queryKeys.workspace, current);
  await render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={en}>
        <OverviewNameSettings />
      </IntlProvider>
    </QueryClientProvider>,
  );
  return client;
}

// The row and the sheet's field share their label; the field is found by its test id.
const field = () => screen.getByTestId('overview-name-field');

beforeEach(() => {
  jest.resetAllMocks();
});

describe('Settings: name of your overview (#490)', () => {
  it('shows the fallback when no name is saved, and no Clear name in the sheet', async () => {
    await mounted(unnamed);
    const row = screen.getByRole('button', { name: 'Name of your overview' });
    expect(row.props.accessibilityHint).toBe('All portfolios');
    expect(screen.getByText('All portfolios')).toBeTruthy();
    await fireEvent.press(row);
    expect(field()).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Clear name' })).toBeNull();
  });

  it('shows the saved name and offers Clear name in the edit sheet', async () => {
    await mounted({ ...unnamed, name: 'Family Hug' });
    expect(screen.getByText('Family Hug')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Name of your overview' }));
    expect(field().props.value).toBe('Family Hug');
    expect(screen.getByRole('button', { name: 'Clear name' })).toBeTruthy();
  });

  it('closes the sheet and shows the new name in the row after Save', async () => {
    jest
      .mocked(typedApi.put)
      .mockResolvedValueOnce({ ...unnamed, name: 'Family Hug', version: 4 } as never);
    await mounted(unnamed);
    await fireEvent.press(screen.getByRole('button', { name: 'Name of your overview' }));
    await fireEvent.changeText(field(), 'Family Hug');
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Family Hug')).toBeTruthy();
    expect(screen.queryByTestId('overview-name-field')).toBeNull();
    expect(
      screen.getByRole('button', { name: 'Name of your overview' }).props.accessibilityHint,
    ).toBe('Family Hug');
  });
});
