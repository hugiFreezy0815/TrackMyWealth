import { Platform } from 'react-native';

import { installBackForwardGuard, replaceDocument } from './web-document';

const ORIGINAL_OS = Platform.OS;
const MARKER = 'trackmywealth.sign-out';

function fakeStorage() {
  const values = new Map<string, string>();
  return {
    values,
    getItem: jest.fn((key: string) => values.get(key) ?? null),
    setItem: jest.fn((key: string, value: string) => void values.set(key, value)),
    removeItem: jest.fn((key: string) => void values.delete(key)),
  };
}

const location = { replace: jest.fn(), reload: jest.fn() };
const listeners = new Map<string, (event: { persisted: boolean }) => void>();
const localStore = fakeStorage();
const sessionStore = fakeStorage();
let randomByte = 0;
const windowStub = {
  localStorage: localStore,
  sessionStorage: sessionStore,
  crypto: {
    getRandomValues: (bytes: Uint8Array) => {
      randomByte += 1;
      return bytes.fill(randomByte);
    },
  },
  location,
  addEventListener: jest.fn((type: string, listener: (event: { persisted: boolean }) => void) =>
    listeners.set(type, listener),
  ),
  removeEventListener: jest.fn((type: string) => listeners.delete(type)),
};
let originalWindow: PropertyDescriptor | undefined;

const restore = () => listeners.get('pageshow')?.({ persisted: true });
const failing = () => {
  throw new Error('QuotaExceededError');
};

beforeEach(() => {
  originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
  Object.defineProperty(globalThis, 'window', { value: windowStub, configurable: true });
  Platform.OS = 'web';
});
afterEach(() => {
  Platform.OS = ORIGINAL_OS;
  if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow);
  listeners.clear();
  localStore.values.clear();
  sessionStore.values.clear();
  jest.clearAllMocks();
});

describe('sign-out on web', () => {
  it('records a new sign-out marker and replaces the document', () => {
    localStore.values.set(MARKER, 'before');
    sessionStore.values.set(MARKER, 'before');

    replaceDocument('/overview');

    expect(localStore.values.get(MARKER)).not.toBe('before');
    expect(sessionStore.values.get(MARKER)).toBe(localStore.values.get(MARKER));
    expect(location.replace).toHaveBeenCalledWith('/overview');
  });

  it('removes the old marker when the new one cannot be written', () => {
    localStore.values.set(MARKER, 'before');
    localStore.setItem.mockImplementationOnce(failing);

    replaceDocument('/overview');

    expect(localStore.values.has(MARKER)).toBe(false);
    expect(location.replace).toHaveBeenCalledWith('/overview');
  });

  it('leaves native apps alone', () => {
    Platform.OS = 'ios';
    replaceDocument('/overview');
    expect(location.replace).not.toHaveBeenCalled();
  });
});

describe('back-forward cache guard', () => {
  it('keeps a signed-in page when no sign-out happened since it started (#535)', () => {
    installBackForwardGuard(() => true);

    restore();

    expect(location.reload).not.toHaveBeenCalled();
  });

  it('reloads a restored page that is signed out', () => {
    installBackForwardGuard(() => false);

    restore();

    expect(location.reload).toHaveBeenCalledTimes(1);
  });

  it('reloads a signed-in page when another document signed out after it started', () => {
    installBackForwardGuard(() => true);

    replaceDocument('/overview');
    restore();

    expect(location.reload).toHaveBeenCalledTimes(1);
  });

  it('reloads a signed-in page when the sign-out could not write its marker', () => {
    installBackForwardGuard(() => true);
    localStore.setItem.mockImplementationOnce(failing);
    sessionStore.setItem.mockImplementationOnce(failing);

    replaceDocument('/overview');
    restore();

    expect(location.reload).toHaveBeenCalledTimes(1);
  });

  it('reloads a signed-in page that cannot read the marker', () => {
    installBackForwardGuard(() => true);
    sessionStore.getItem.mockImplementationOnce(failing);

    restore();

    expect(location.reload).toHaveBeenCalledTimes(1);
  });

  it('reloads a signed-in page that could not store a marker at start-up', () => {
    localStore.setItem.mockImplementationOnce(failing);
    installBackForwardGuard(() => true);

    restore();

    expect(location.reload).toHaveBeenCalledTimes(1);
  });

  it('ignores a normal page load and can be removed', () => {
    const remove = installBackForwardGuard(() => false);

    listeners.get('pageshow')?.({ persisted: false });
    expect(location.reload).not.toHaveBeenCalled();

    remove();
    expect(windowStub.removeEventListener).toHaveBeenCalledWith('pageshow', expect.any(Function));
  });

  it('installs nothing in native apps', () => {
    Platform.OS = 'android';
    installBackForwardGuard(() => false);
    expect(windowStub.addEventListener).not.toHaveBeenCalled();
  });
});
