import { act, renderHook } from '@testing-library/react-native';

import { useLocalDay } from './use-local-day';

describe('useLocalDay', () => {
  afterEach(() => jest.useRealTimers());

  it('moves on to the next day at local midnight', async () => {
    jest.useFakeTimers({ now: new Date(2026, 10, 9, 23, 59, 0) });
    const { result } = await renderHook(() => useLocalDay());
    expect(result.current).toBe('2026-11-09');
    await act(async () => jest.advanceTimersByTime(59_000));
    expect(result.current).toBe('2026-11-09');
    await act(async () => jest.advanceTimersByTime(1_000));
    expect(result.current).toBe('2026-11-10');
  });
});
