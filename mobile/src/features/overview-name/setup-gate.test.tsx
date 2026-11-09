import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';

import { usePortfolios, useWorkspace } from '@/api/queries/resources';
import { completeOverviewStep, hasCompletedOverviewStep } from './device-step';
import en from '@/i18n/en.json';
import { OverviewSetupGate } from './setup-gate';

jest.mock('@/global.css', () => ({}));
jest.mock('@/api/queries/resources', () => ({
  useWorkspace: jest.fn(), usePortfolios: jest.fn(),
}));
jest.mock('./device-step', () => ({
  setupStepKey: (user: string, overview: string) => user + '/' + overview,
  hasCompletedOverviewStep: jest.fn(async () => false),
  completeOverviewStep: jest.fn(async () => {}),
}));

const workspace = { id: 'w-1', name: null, currency: 'CHF', version: 1 };
const memberPortfolio = { personalAssetsDefault: true, activeAccountCount: 0 };

async function mounted() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={en}>
        <OverviewSetupGate userId="member-1">
          <>{'Financial overview'}</>
        </OverviewSetupGate>
      </IntlProvider>
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  onlineManager.setOnline(true);
  jest.clearAllMocks();
  jest.mocked(useWorkspace).mockReturnValue({
    isPending: false, isError: false, data: workspace,
  } as ReturnType<typeof useWorkspace>);
  jest.mocked(usePortfolios).mockReturnValue({
    isPending: false, isError: false, data: [memberPortfolio],
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

  it('never blocks an existing member with their own portfolio', async () => {
    jest.mocked(usePortfolios).mockReturnValue({
      isPending: false, isError: false,
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
