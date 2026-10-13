import { renderHook } from '@testing-library/react-native';

import { Colors } from '@/constants/theme';
import { useColorScheme } from '@/hooks/use-color-scheme';
import { useTheme } from '@/hooks/use-theme';

// theme.ts imports the web stylesheet, which Jest cannot parse.
jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-color-scheme', () => ({ useColorScheme: jest.fn() }));

const mockedUseColorScheme = jest.mocked(useColorScheme);

describe('useTheme', () => {
  afterEach(() => {
    jest.resetAllMocks();
  });

  it('returns the dark palette when the system scheme is dark', async () => {
    mockedUseColorScheme.mockReturnValue('dark');

    const { result } = await renderHook(() => useTheme());

    expect(result.current).toBe(Colors.dark);
  });

  it('returns the light palette when the system scheme is light', async () => {
    mockedUseColorScheme.mockReturnValue('light');

    const { result } = await renderHook(() => useTheme());

    expect(result.current).toBe(Colors.light);
  });

  // React Native 0.88 returns null when the native Appearance module is unavailable.
  it('falls back to the light palette when no scheme is reported', async () => {
    mockedUseColorScheme.mockReturnValue(null);

    const { result } = await renderHook(() => useTheme());

    expect(result.current).toBe(Colors.light);
  });
});
