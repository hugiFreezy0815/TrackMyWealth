import { renderHook } from '@testing-library/react-native';
import * as ReactNative from 'react-native';

import { useColorScheme } from './use-color-scheme.web';

describe('useColorScheme (web)', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('renders light first, then the client scheme once hydrated', async () => {
    jest.spyOn(ReactNative, 'useColorScheme').mockReturnValue('dark');
    const renders: (string | null | undefined)[] = [];

    await renderHook(() => {
      const scheme = useColorScheme();
      renders.push(scheme);
      return scheme;
    });

    // The first render must match the static (server-rendered) HTML, which is always light.
    expect(renders[0]).toBe('light');
    expect(renders[renders.length - 1]).toBe('dark');
  });

  it('passes a missing client scheme through so callers can fall back', async () => {
    jest.spyOn(ReactNative, 'useColorScheme').mockReturnValue(null);

    const { result } = await renderHook(() => useColorScheme());

    expect(result.current).toBeNull();
  });
});
