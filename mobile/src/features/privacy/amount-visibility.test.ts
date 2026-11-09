import { Platform } from 'react-native';

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(async () => {}),
}));

type Store = typeof import('./amount-visibility');
type SecureStoreMock = { getItemAsync: jest.Mock; setItemAsync: jest.Mock };

const ORIGINAL_OS = Platform.OS;
const browserStorage = new Map<string, string>();
const localStorageStub = {
  getItem: jest.fn((key: string) => browserStorage.get(key) ?? null),
  setItem: jest.fn((key: string, value: string) => void browserStorage.set(key, value)),
};

/** The preference is module state (one per app start): every test gets a fresh start. */
function freshStart(os: typeof Platform.OS): { store: Store; secureStore: SecureStoreMock } {
  Platform.OS = os;
  let store!: Store;
  let secureStore!: SecureStoreMock;
  jest.isolateModules(() => {
    store = jest.requireActual<Store>('./amount-visibility');
    secureStore = jest.requireMock<SecureStoreMock>('expo-secure-store');
  });
  return { store, secureStore };
}

beforeEach(() => {
  Object.defineProperty(globalThis, 'localStorage', {
    value: localStorageStub,
    configurable: true,
  });
});
afterEach(() => {
  Platform.OS = ORIGINAL_OS;
  browserStorage.clear();
  jest.clearAllMocks();
  Reflect.deleteProperty(globalThis, 'localStorage');
});

describe('Hide amounts preference (FR-CLI-005)', () => {
  it('keeps amounts hidden until the saved preference is read', async () => {
    const { store } = freshStart('ios');

    expect(store.amountsHidden()).toBe(true);
  });

  it('stays hidden after a restart when hiding was saved on the device', async () => {
    const { store, secureStore } = freshStart('ios');
    secureStore.getItemAsync.mockResolvedValue('true');

    await store.restoreAmountVisibility();

    expect(secureStore.getItemAsync).toHaveBeenCalledWith(store.HIDE_AMOUNTS_KEY);
    expect(store.amountsHidden()).toBe(true);
  });

  it('shows amounts on the first start, when nothing is saved yet', async () => {
    const { store, secureStore } = freshStart('android');
    secureStore.getItemAsync.mockResolvedValue(null);

    await store.restoreAmountVisibility();

    expect(store.amountsHidden()).toBe(false);
  });

  it('stays private when the device storage cannot be read', async () => {
    const { store, secureStore } = freshStart('ios');
    secureStore.getItemAsync.mockRejectedValue(new Error('keychain locked'));

    await store.restoreAmountVisibility();

    expect(store.amountsHidden()).toBe(true);
  });

  it('persists a change and notifies every subscriber', async () => {
    const { store, secureStore } = freshStart('ios');
    const listener = jest.fn();
    const unsubscribe = store.subscribeAmounts(listener);

    await store.setAmountsHidden(false);

    expect(store.amountsHidden()).toBe(false);
    expect(listener).toHaveBeenCalled();
    expect(secureStore.setItemAsync).toHaveBeenCalledWith(store.HIDE_AMOUNTS_KEY, 'false');
    unsubscribe();
  });

  it('never lets a slow start-up read overwrite the choice made meanwhile', async () => {
    const { store, secureStore } = freshStart('ios');
    let finishRead!: (value: string) => void;
    secureStore.getItemAsync.mockReturnValue(
      new Promise<string>((resolve) => {
        finishRead = resolve;
      }),
    );

    const restoring = store.restoreAmountVisibility();
    await store.setAmountsHidden(true);
    finishRead('false');
    await restoring;

    expect(store.amountsHidden()).toBe(true);
  });

  it('reads the device only once per start', async () => {
    const { store, secureStore } = freshStart('ios');
    secureStore.getItemAsync.mockResolvedValue('true');

    await Promise.all([store.restoreAmountVisibility(), store.restoreAmountVisibility()]);

    expect(secureStore.getItemAsync).toHaveBeenCalledTimes(1);
  });

  it('remembers the choice in the browser on web, never in the secure store', async () => {
    const first = freshStart('web');
    await first.store.setAmountsHidden(true);
    expect(first.secureStore.setItemAsync).not.toHaveBeenCalled();
    expect(browserStorage.get(first.store.HIDE_AMOUNTS_KEY)).toBe('true');

    const restarted = freshStart('web');
    await restarted.store.restoreAmountVisibility();

    expect(restarted.store.amountsHidden()).toBe(true);
  });

  it('stays private on web when the browser blocks storage', async () => {
    const { store } = freshStart('web');
    localStorageStub.getItem.mockImplementationOnce(() => {
      throw new Error('SecurityError');
    });

    await store.restoreAmountVisibility();

    expect(store.amountsHidden()).toBe(true);
  });
});
