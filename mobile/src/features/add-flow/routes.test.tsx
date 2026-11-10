// Route tests live outside src/app: Expo Router would treat a test file there as a route.
import { screen, waitFor } from '@testing-library/react-native';

import AddAccountRoute from '@/app/portfolios/[portfolioId]/accounts/new';
import { showToast } from '@/features/shell/toast';
import en from '@/i18n/en.json';
import { renderWithIntl } from '@/i18n/test-utils';

import { problem } from './test-fixtures';
import { usePortfolio } from './use-create';

const mockReplace = jest.fn();
jest.mock('@/global.css', () => ({}));
jest.mock('expo-router', () => ({
  useRouter: () => ({ replace: mockReplace }),
  useLocalSearchParams: () => ({ portfolioId: 'p-gone' }),
}));
jest.mock('@/features/shell/screen', () => ({
  ShellScreen: function ShellScreen({ children }: { children: React.ReactNode }) {
    return children;
  },
}));
jest.mock('@/features/shell/toast', () => ({ showToast: jest.fn() }));
jest.mock('./use-create', () => ({ usePortfolio: jest.fn() }));

function portfolioQuery(state: Record<string, unknown>) {
  return {
    isPending: false,
    isError: false,
    data: undefined,
    refetch: jest.fn(),
    ...state,
  } as never;
}

beforeEach(() => jest.clearAllMocks());

describe('Add account route (#491)', () => {
  it('returns to the overview with a toast when the portfolio no longer exists', async () => {
    jest
      .mocked(usePortfolio)
      .mockReturnValue(portfolioQuery({ isError: true, error: problem(404, 'NOT_FOUND') }));
    await renderWithIntl(<AddAccountRoute />);
    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/overview'));
    expect(showToast).toHaveBeenCalledWith('addAccount.portfolioGone');
    expect(screen.queryByText(en['problem.NOT_FOUND'])).toBeNull();
  });

  it('offers a retry for other load failures and stays', async () => {
    jest
      .mocked(usePortfolio)
      .mockReturnValue(portfolioQuery({ isError: true, error: problem(500, 'INTERNAL') }));
    await renderWithIntl(<AddAccountRoute />);
    expect(screen.getByText(en['problem.INTERNAL'])).toBeTruthy();
    expect(screen.getByRole('button', { name: en['error.retry'] })).toBeTruthy();
    expect(mockReplace).not.toHaveBeenCalled();
    expect(showToast).not.toHaveBeenCalled();
  });
});
