import { act, renderHook } from '@testing-library/react-native';
import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

import {
  HISTORY_PERIOD_KEY,
  historyPeriodWrites,
  resetHistoryPeriodForTests,
  selectHistoryPeriod,
  useHistoryPeriod,
} from './period';

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(),
}));

const getItem = jest.mocked(SecureStore.getItemAsync);
const setItem = jest.mocked(SecureStore.setItemAsync);
const originalOs = Platform.OS;

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function mount() {
  const view = await renderHook(() => useHistoryPeriod());
  // Let the restore read settle.
  await act(async () => {});
  return view;
}

beforeEach(() => {
  getItem.mockReset().mockResolvedValue(null);
  setItem.mockReset().mockResolvedValue();
  resetHistoryPeriodForTests();
});

afterEach(() => {
  Platform.OS = originalOs;
});

describe('history period (S1 #11: default 1M, remembered on the device)', () => {
  it('defaults to 1M when nothing is saved', async () => {
    const { result } = await mount();
    expect(result.current).toBe('1M');
    expect(getItem).toHaveBeenCalledWith(HISTORY_PERIOD_KEY);
  });

  it('restores the saved period from the secure store', async () => {
    getItem.mockResolvedValue('YTD');
    const { result } = await mount();
    expect(result.current).toBe('YTD');
  });

  it('ignores a saved value that is not a known period', async () => {
    getItem.mockResolvedValue('5Y');
    const { result } = await mount();
    expect(result.current).toBe('1M');
  });

  it('saves a selection and shares it with every mounted screen', async () => {
    const first = await mount();
    const second = await mount();
    await act(async () => selectHistoryPeriod('1Y'));
    await historyPeriodWrites();
    expect(first.result.current).toBe('1Y');
    expect(second.result.current).toBe('1Y');
    expect(setItem).toHaveBeenLastCalledWith(HISTORY_PERIOD_KEY, '1Y');
  });

  it('writes rapid selections in order, so the last one is saved', async () => {
    await mount();
    await act(async () => {
      selectHistoryPeriod('1W');
      selectHistoryPeriod('MAX');
    });
    await historyPeriodWrites();
    expect(setItem.mock.calls.map(([, value]) => value)).toEqual(['1W', 'MAX']);
  });

  it('keeps a choice made while the saved period is still being read', async () => {
    const read = deferred<string | null>();
    getItem.mockReturnValue(read.promise);
    const { result } = await renderHook(() => useHistoryPeriod());
    await act(async () => selectHistoryPeriod('1W'));
    await act(async () => read.resolve('MAX'));
    expect(result.current).toBe('1W');
  });

  it('retries the read on the next mount after the store failed', async () => {
    getItem.mockRejectedValueOnce(new Error('keychain locked')).mockResolvedValue('1Y');
    const first = await mount();
    expect(first.result.current).toBe('1M');
    const second = await mount();
    expect(getItem).toHaveBeenCalledTimes(2);
    expect(second.result.current).toBe('1Y');
  });

  it('keeps the selection for the session when saving fails', async () => {
    setItem.mockRejectedValue(new Error('full'));
    const { result } = await mount();
    await act(async () => selectHistoryPeriod('YTD'));
    await expect(historyPeriodWrites()).resolves.toBeUndefined();
    expect(result.current).toBe('YTD');
  });

  it('uses localStorage on the web', async () => {
    Platform.OS = 'web';
    const storage = new Map<string, string>([[HISTORY_PERIOD_KEY, '1W']]);
    const localStorage = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => void storage.set(key, value),
    };
    Object.defineProperty(globalThis, 'localStorage', { value: localStorage, configurable: true });
    try {
      const { result } = await mount();
      expect(result.current).toBe('1W');
      await act(async () => selectHistoryPeriod('MAX'));
      await historyPeriodWrites();
      expect(storage.get(HISTORY_PERIOD_KEY)).toBe('MAX');
      expect(getItem).not.toHaveBeenCalled();
      expect(setItem).not.toHaveBeenCalled();
    } finally {
      Object.defineProperty(globalThis, 'localStorage', { value: undefined, configurable: true });
    }
  });
});
