import * as SecureStore from 'expo-secure-store';
import {
  completeOverviewStep, hasCompletedOverviewStep, setupStepKey,
} from './device-step';

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => {}),
}));

describe('overview-name step choice on one device', () => {
  beforeEach(() => jest.clearAllMocks());

  it('scopes the choice to both the user and overview id', () => {
    expect(setupStepKey('user-1', 'overview-1')).not.toBe(
      setupStepKey('user-2', 'overview-1'),
    );
    expect(setupStepKey('user-1', 'overview-1')).not.toBe(
      setupStepKey('user-1', 'overview-2'),
    );
  });

  it('reads and writes the native device marker', async () => {
    jest.mocked(SecureStore.getItemAsync).mockResolvedValueOnce('done');
    expect(await hasCompletedOverviewStep('user', 'overview')).toBe(true);
    await completeOverviewStep('user', 'overview');
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith(
      setupStepKey('user', 'overview'), 'done',
    );
  });

  it('never invents completion if storage is inaccessible', async () => {
    jest.mocked(SecureStore.getItemAsync).mockRejectedValueOnce(new Error('locked'));
    expect(await hasCompletedOverviewStep('user', 'overview')).toBe(false);
  });
});
