import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';
import { Pressable, Text } from 'react-native';

import { ApiError } from '@/api/client';

import { usePortfolios, useWorkspace } from '@/api/queries/resources';
import { typedApi } from '@/api/typed-api';
import en from '@/i18n/en.json';

import { completeOverviewStep, hasCompletedOverviewStep } from './device-step';
import { OverviewSetupGate } from './setup-gate';

jest.mock('@/global.css', () => ({}));
jest.mock('@/api/queries/resources', () => ({
  useWorkspace: jest.fn(),
  usePortfolios: jest.fn(),
}));
jest.mock('@/api/typed-api', () => ({ typedApi: { get: jest.fn(), put: jest.fn() } }));
jest.mock('./device-step', () => ({
  setupStepKey: (user: string, overview: string) => user + '/' + overview,
  hasCompletedOverviewStep: jest.fn(async () => false),
  completeOverviewStep: jest.fn(async () => {}),
}));

const workspace = { id: 'w-1', name: null, currency: 'CHF', version: 1 };
const memberPortfolio = { personalAssetsDefault: true, activeAccountCount: 0 };

async function mounted(children: React.ReactNode = <Text>Financial overview</Text>) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false, gcTime: Infinity },
    },
  });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={en}>
        <OverviewSetupGate userId="member-1">{children}</OverviewSetupGate>
      </IntlProvider>
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  onlineManager.setOnline(true);
  jest.clearAllMocks();
  jest.mocked(useWorkspace).mockReturnValue({
    isPending: false,
    isError: false,
    data: workspace,
  } as ReturnType<typeof useWorkspace>);
  jest.mocked(usePortfolios).mockReturnValue({
    isPending: false,
    isError: false,
    data: [memberPortfolio],
  } as unknown as ReturnType<typeof usePortfolios>);
});

describe('first-run overview name gate', () => {
  it('shows the optional step only for a member who has not completed it', async () => {
    await mounted();
    expect(await screen.findByText('Name your overview')).toBeTruthy();
    expect(screen.queryByText('Financial overview')).toBeNull();
    expect(hasCompletedOverviewStep).toHaveBeenCalledWith('member-1', 'w-1');
  });

  it('persists Skip on this device and continues to the actual overview', async () => {
    await mounted();
    await screen.findByText('Name your overview');
    await fireEvent.press(screen.getByRole('button', { name: 'Skip for now' }));
    expect(completeOverviewStep).toHaveBeenCalledWith('member-1', 'w-1');
    expect(screen.getByText('Financial overview')).toBeTruthy();
  });

  it('saves a typed name on Continue, remembers the step and opens the overview', async () => {
    jest
      .mocked(typedApi.put)
      .mockResolvedValueOnce({ ...workspace, name: 'Family Hug', version: 2 } as never);
    await mounted();
    await fireEvent.changeText(await screen.findByLabelText('Name of your overview'), 'Family Hug');
    await fireEvent.press(screen.getByRole('button', { name: 'Continue' }));
    expect(typedApi.put).toHaveBeenCalledWith('/api/v1/workspace/name', {
      version: 1,
      body: { name: 'Family Hug' },
    });
    expect(completeOverviewStep).toHaveBeenCalledWith('member-1', 'w-1');
    expect(await screen.findByText('Financial overview')).toBeTruthy();
  });

  it('sends nothing on Continue with an empty name and unchanged currency', async () => {
    await mounted();
    await fireEvent.press(await screen.findByRole('button', { name: 'Continue' }));
    expect(typedApi.put).not.toHaveBeenCalled();
    expect(completeOverviewStep).toHaveBeenCalledWith('member-1', 'w-1');
    expect(await screen.findByText('Financial overview')).toBeTruthy();
  });

  it('shows neither the step nor the overview while the device choice is still being read', async () => {
    jest.mocked(hasCompletedOverviewStep).mockReturnValueOnce(new Promise<boolean>(() => {}));
    await mounted();
    expect(screen.queryByText('Name your overview')).toBeNull();
    expect(screen.queryByText('Financial overview')).toBeNull();
  });

  it('skips the step a member already finished on this device', async () => {
    jest.mocked(hasCompletedOverviewStep).mockResolvedValueOnce(true);
    await mounted();
    expect(await screen.findByText('Financial overview')).toBeTruthy();
    expect(screen.queryByText('Name your overview')).toBeNull();
  });

  it('never blocks an existing member with their own portfolio', async () => {
    jest.mocked(usePortfolios).mockReturnValue({
      isPending: false,
      isError: false,
      data: [{ personalAssetsDefault: false, activeAccountCount: 1 }],
    } as ReturnType<typeof usePortfolios>);
    await mounted();
    expect(await screen.findByText('Financial overview')).toBeTruthy();
    expect(screen.queryByText('Name your overview')).toBeNull();
  });

  it('keeps an offline member on the existing protected screen', async () => {
    onlineManager.setOnline(false);
    await mounted();
    expect(screen.getByText('Financial overview')).toBeTruthy();
  });
});

describe('first-run gate when the workspace or portfolios request fails', () => {
  const invalidSort = () =>
    new ApiError('Bad request', 400, { status: 400, title: 'Bad request', code: 'INVALID_SORT' });
  const calls = (path: string) => jest.mocked(typedApi.get).mock.calls.filter(([p]) => p === path);
  let workspaceFails = true;

  /** Like the real overview: it reads the same queries and offers a retry. */
  function Overview() {
    const workspaceQuery = useWorkspace();
    const portfoliosQuery = usePortfolios();
    const failedQuery = workspaceQuery.isError ? workspaceQuery : portfoliosQuery;
    return (
      <>
        <Text>Financial overview</Text>
        {failedQuery.isError ? (
          <>
            <Text>{workspaceQuery.isError ? 'Workspace failed' : 'Portfolios failed'}</Text>
            <Pressable accessibilityRole="button" onPress={() => void failedQuery.refetch()}>
              <Text>Retry</Text>
            </Pressable>
          </>
        ) : null}
      </>
    );
  }

  beforeEach(() => {
    workspaceFails = true;
    const actual =
      jest.requireActual<typeof import('@/api/queries/resources')>('@/api/queries/resources');
    jest.mocked(useWorkspace).mockImplementation(actual.useWorkspace);
    jest.mocked(usePortfolios).mockImplementation(actual.usePortfolios);
    jest.mocked(typedApi.get).mockImplementation((async (path: string) => {
      if (path === '/api/v1/portfolios') throw invalidSort();
      if (workspaceFails) throw invalidSort();
      return workspace;
    }) as never);
  });

  it('shows the screen with its error once instead of fetching again and again', async () => {
    await mounted(<Overview />);
    expect(await screen.findByRole('button', { name: 'Retry' })).toBeTruthy();
    await act(() => new Promise((resolve) => setTimeout(resolve, 300)));
    // The first request, and one more when the screen subscribes to the failed query.
    expect(calls('/api/v1/workspace').length).toBeLessThanOrEqual(2);
    expect(calls('/api/v1/portfolios').length).toBeLessThanOrEqual(2);
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
  });

  it('decides as before once a retry succeeds', async () => {
    jest.mocked(typedApi.get).mockImplementation((async (path: string) => {
      if (path === '/api/v1/portfolios') return [memberPortfolio];
      if (workspaceFails) throw invalidSort();
      return workspace;
    }) as never);
    await mounted(<Overview />);
    const retry = await screen.findByRole('button', { name: 'Retry' });
    workspaceFails = false;
    await fireEvent.press(retry);
    expect(await screen.findByText('Name your overview')).toBeTruthy();
  });

  it('keeps the overview mounted during a portfolios-only refetch and recovers on Retry', async () => {
    const portfoliosPath = '/api/v1/portfolios';
    let portfoliosFail = true;
    let finishAutomaticRefetch = () => {};
    const automaticRefetch = new Promise<void>((resolve) => {
      finishAutomaticRefetch = resolve;
    });
    jest.mocked(typedApi.get).mockImplementation((async (path: string) => {
      if (path === '/api/v1/workspace') return workspace;
      if (path !== portfoliosPath) throw new Error(`Unexpected GET ${path}`);
      if (!portfoliosFail) return [memberPortfolio];
      if (calls(portfoliosPath).length === 2) await automaticRefetch;
      throw invalidSort();
    }) as never);

    await mounted(<Overview />);
    await waitFor(() => {
      expect(calls(portfoliosPath)).toHaveLength(2);
      expect(screen.getByText('Financial overview')).toBeTruthy();
      expect(screen.queryByText('Portfolios failed')).toBeNull();
    });

    await act(async () => finishAutomaticRefetch());
    expect(await screen.findByText('Portfolios failed')).toBeTruthy();
    expect(screen.getByText('Financial overview')).toBeTruthy();
    const retry = screen.getByRole('button', { name: 'Retry' });
    expect(calls(portfoliosPath).length).toBeLessThanOrEqual(2);
    expect(screen.queryByText('Name your overview')).toBeNull();

    portfoliosFail = false;
    await fireEvent.press(retry);
    expect(await screen.findByText('Name your overview')).toBeTruthy();
    expect(calls(portfoliosPath)).toHaveLength(3);
  });
});
