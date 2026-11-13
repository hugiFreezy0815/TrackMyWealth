import { Platform } from 'react-native';

import { installBackForwardGuard, replaceDocument } from './web-document';

const ORIGINAL_OS = Platform.OS;
const location = { replace: jest.fn(), reload: jest.fn() };
const listeners = new Map<string, (event: { persisted: boolean }) => void>();
const storage = new Map<string, string>();
const windowStub = {
  localStorage: {
    getItem: jest.fn((key: string) => storage.get(key) ?? null),
    setItem: jest.fn((key: string, value: string) => storage.set(key, value)),
  },
  location,
  addEventListener: jest.fn((type: string, listener: (event: { persisted: boolean }) => void) =>
    listeners.set(type, listener),
  ),
  removeEventListener: jest.fn((type: string) => listeners.delete(type)),
};
let originalWindow: PropertyDescriptor | undefined;

beforeEach(() => {
  originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
  Object.defineProperty(globalThis, 'window', { value: windowStub, configurable: true });
});
afterEach(() => {
  Platform.OS = ORIGINAL_OS;
  if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow);
  listeners.clear();
  storage.clear();
  jest.clearAllMocks();
});

describe('sign-out on web', () => {
  it('replaces the document, so no in-memory state of the session survives', () => {
    Platform.OS = 'web';
    replaceDocument('/overview');
    expect(location.replace).toHaveBeenCalledWith('/overview');
  });

  it('leaves native apps alone', () => {
    Platform.OS = 'ios';
    replaceDocument('/overview');
    expect(location.replace).not.toHaveBeenCalled();
  });
});

describe('back-forward cache guard', () => {
  it('reloads any restored page', () => {
    Platform.OS = 'web';
    installBackForwardGuard();

    listeners.get('pageshow')?.({ persisted: true });

    expect(location.reload).toHaveBeenCalledTimes(1);
  });

  it('reloads an earlier document when sign-out cannot update its readable marker', () => {
    Platform.OS = 'web';
    const key = 'trackmywealth.sign-out';
    storage.set(key, 'old-marker');
    windowStub.localStorage.setItem.mockImplementationOnce(() => {
      throw new Error('storage quota exceeded');
    });
    installBackForwardGuard();

    // Simulate sign-out from another document that cannot update the shared marker.
    expect(() => windowStub.localStorage.setItem(key, 'new-marker')).toThrow();
    expect(windowStub.localStorage.getItem(key)).toBe('old-marker');

    listeners.get('pageshow')?.({ persisted: true });

    expect(location.reload).toHaveBeenCalledTimes(1);
  });

  it('ignores a normal page load and can be removed', () => {
    Platform.OS = 'web';
    const remove = installBackForwardGuard();

    listeners.get('pageshow')?.({ persisted: false });
    expect(location.reload).not.toHaveBeenCalled();

    remove();
    expect(listeners.has('pageshow')).toBe(false);
  });

  it('installs nothing in native apps', () => {
    Platform.OS = 'android';
    installBackForwardGuard();
    expect(windowStub.addEventListener).not.toHaveBeenCalled();
  });
});
