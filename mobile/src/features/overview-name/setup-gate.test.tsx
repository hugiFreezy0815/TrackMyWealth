import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';
import { Text } from 'react-native';

import { ApiError } from '@/api/client';
import { usePortfolios, useWorkspace } from '@/api/queries/resources';
import type { components } from '@/api/schema';
import { typedApi } from '@/api/typed-api';
import { OverviewScreen } from '@/features/overview/overview-screen';
import en from '@/i18n/en.json';

import { completeOverviewStep, hasCompletedOverviewStep } from './device-step';
import { OverviewSetupGate } from './setup-gate';

jest.mock('@/global.css', () => ({}));
jest.mock('@/api/queries/resources', () => ({
  ...jest.requireActual('@/api/queries/resources'),
  useWorkspace: jest.fn(),
  usePortfolios: jest.fn(),
}));
jest.mock('@/features/privacy/amount-visibility', () => ({
  useAmountsHidden: jest.fn(() => false),
}));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
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

describe('first-run gate when the workspace or portfolios request fails (#567)', () => {
  const WORKSPACE = '/api/v1/workspace';
  const PORTFOLIOS = '/api/v1/portfolios';
  const unavailable = () =>
    new ApiError('Service unavailable', 503, { status: 503, title: 'Service unavailable' });
  const calls = (path: string) => jest.mocked(typedApi.get).mock.calls.filter(([p]) => p === path);
  const retryButtons = () => screen.queryAllByRole('button', { name: 'Try again' });
  /** Lets React Query run its refetches and notifications, as a loop would within this time. */
  const settle = () => act(() => jest.advanceTimersByTimeAsync(5_000));
  /** A full list item, because the real overview renders the portfolio rows. */
  const portfolio = (
    overrides: Partial<components['schemas']['PortfolioListItemResponse']>,
  ): components['schemas']['PortfolioListItemResponse'] => ({
    id: 'portfolio-1',
    version: 1,
    name: 'Personal assets',
    catalogueInstitutionId: null,
    institutionType: 'OTHER',
    country: null,
    logoUrl: null,
    portfolioType: 'PERSONAL_ASSETS',
    portfolioTypeOverridden: false,
    personalAssetsDefault: true,
    activeAccountCount: 0,
    portfolioCurrency: 'CHF',
    total: '0.00',
    workspaceCurrency: 'CHF',
    totalInWorkspaceCurrency: '0.00',
    complete: true,
    valueStatus: 'CURRENT',
    asOf: '2026-11-08',
    institutionName: null,
    change: null,
    ...overrides,
  });
  type Respond = () => Promise<unknown>;
  let respond: Record<string, Respond>;

  /** The real overview reads the same two queries, and its banners offer the retry. */
  const overview = (
    <OverviewScreen
      onAddPortfolio={jest.fn()}
      onReviewAccount={jest.fn()}
      onPortfolio={jest.fn()}
    />
  );

  beforeEach(() => {
    jest.useFakeTimers();
    const actual =
      jest.requireActual<typeof import('@/api/queries/resources')>('@/api/queries/resources');
    jest.mocked(useWorkspace).mockImplementation(actual.useWorkspace);
    jest.mocked(usePortfolios).mockImplementation(actual.usePortfolios);
    respond = {
      [WORKSPACE]: async () => workspace,
      [PORTFOLIOS]: async () => [portfolio({})],
    };
    // The overview's other figures never answer, so every banner belongs to a gate query.
    jest
      .mocked(typedApi.get)
      .mockImplementation(((path: string) => respond[path]?.() ?? new Promise(() => {})) as never);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('shows the overview with both errors once instead of fetching again and again', async () => {
    respond[WORKSPACE] = async () => Promise.reject(unavailable());
    respond[PORTFOLIOS] = async () => Promise.reject(unavailable());
    await mounted(overview);
    expect(await screen.findAllByRole('button', { name: 'Try again' })).toHaveLength(2);
    await settle();
    // The first request, and one more when the overview subscribes to the failed query.
    expect(calls(WORKSPACE)).toHaveLength(2);
    expect(calls(PORTFOLIOS)).toHaveLength(2);
    expect(retryButtons()).toHaveLength(2);
  });

  it('shows the first-run step to a new member once a workspace retry succeeds', async () => {
    let workspaceFails = true;
    respond[WORKSPACE] = async () => {
      if (workspaceFails) throw unavailable();
      return workspace;
    };
    await mounted(overview);
    const retry = await screen.findByRole('button', { name: 'Try again' });
    await settle();
    expect(calls(WORKSPACE)).toHaveLength(2);

    workspaceFails = false;
    await fireEvent.press(retry);
    expect(await screen.findByText('Name your overview')).toBeTruthy();
    expect(calls(WORKSPACE)).toHaveLength(3);
  });

  it('keeps an existing member on the overview once a workspace retry succeeds', async () => {
    let workspaceFails = true;
    respond[WORKSPACE] = async () => {
      if (workspaceFails) throw unavailable();
      return workspace;
    };
    respond[PORTFOLIOS] = async () => [
      portfolio({ name: 'Euro Bank', personalAssetsDefault: false, activeAccountCount: 1 }),
    ];
    await mounted(overview);
    const retry = await screen.findByRole('button', { name: 'Try again' });
    await settle();

    workspaceFails = false;
    await fireEvent.press(retry);
    await settle();
    // The retry, and at most one more when the overview mounts again after the gate's decision
    // (this test client has no staleTime). After that the count stays put.
    const afterRetry = calls(WORKSPACE).length;
    expect(afterRetry).toBeLessThanOrEqual(4);
    await settle();
    expect(calls(WORKSPACE)).toHaveLength(afterRetry);
    expect(retryButtons()).toHaveLength(0);
    expect(screen.getByTestId('overview-screen')).toBeTruthy();
    expect(screen.queryByText('Name your overview')).toBeNull();
  });

  it('keeps the overview mounted during a portfolios-only refetch and recovers on Retry', async () => {
    let portfoliosFail = true;
    let finishAutomaticRefetch = () => {};
    const automaticRefetch = new Promise<void>((resolve) => {
      finishAutomaticRefetch = resolve;
    });
    respond[PORTFOLIOS] = async () => {
      if (!portfoliosFail) return [portfolio({})];
      if (calls(PORTFOLIOS).length === 2) await automaticRefetch;
      throw unavailable();
    };

    await mounted(overview);
    await settle();
    // The overview's own refetch is still running: the overview stays, without a banner yet.
    expect(calls(PORTFOLIOS)).toHaveLength(2);
    expect(screen.getByTestId('overview-screen')).toBeTruthy();
    expect(retryButtons()).toHaveLength(0);

    await act(async () => finishAutomaticRefetch());
    const retry = await screen.findByRole('button', { name: 'Try again' });
    await settle();
    expect(calls(PORTFOLIOS)).toHaveLength(2);
    expect(screen.getByTestId('overview-screen')).toBeTruthy();
    expect(screen.queryByText('Name your overview')).toBeNull();

    portfoliosFail = false;
    await fireEvent.press(retry);
    expect(await screen.findByText('Name your overview')).toBeTruthy();
    expect(calls(PORTFOLIOS)).toHaveLength(3);
  });
});
