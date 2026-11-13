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
  crypto: { randomUUID: () => 'sign-out-marker' },
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
    expect(windowStub.localStorage.setItem).toHaveBeenCalledWith(
      'trackmywealth.sign-out',
      'sign-out-marker',
    );
  });

  it('leaves native apps alone', () => {
    Platform.OS = 'ios';
    replaceDocument('/overview');
    expect(location.replace).not.toHaveBeenCalled();
  });
});

describe('back-forward cache guard', () => {
  it('reloads a signed-out page the browser restored from its cache', () => {
    Platform.OS = 'web';
    installBackForwardGuard(() => false);

    listeners.get('pageshow')?.({ persisted: true });

    expect(location.reload).toHaveBeenCalled();
  });

  it('keeps a signed-in page, which a reload would sign out', () => {
    Platform.OS = 'web';
    installBackForwardGuard(() => true);

    listeners.get('pageshow')?.({ persisted: true });

    expect(location.reload).not.toHaveBeenCalled();
  });

  it('reloads a restored signed-in document that predates a sign-out', () => {
    Platform.OS = 'web';
    installBackForwardGuard(() => true);
    replaceDocument('/overview');

    listeners.get('pageshow')?.({ persisted: true });

    expect(location.reload).toHaveBeenCalled();
  });

  it('reloads a restored signed-in document when storage is unavailable', () => {
    Platform.OS = 'web';
    windowStub.localStorage.getItem.mockImplementationOnce(() => {
      throw new Error('storage unavailable');
    });
    installBackForwardGuard(() => true);

    listeners.get('pageshow')?.({ persisted: true });

    expect(location.reload).toHaveBeenCalled();
  });

  it('ignores a normal page load and can be removed', () => {
    Platform.OS = 'web';
    const remove = installBackForwardGuard(() => false);

    listeners.get('pageshow')?.({ persisted: false });
    expect(location.reload).not.toHaveBeenCalled();

    remove();
    expect(listeners.has('pageshow')).toBe(false);
  });

  it('installs nothing in native apps', () => {
    Platform.OS = 'android';
    installBackForwardGuard(() => false);
    expect(windowStub.addEventListener).not.toHaveBeenCalled();
  });
});
