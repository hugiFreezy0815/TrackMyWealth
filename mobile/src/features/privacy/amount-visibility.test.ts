import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import { amountsHidden, restoreAmountVisibility, setAmountsHidden } from './amount-visibility';

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => 'true'),
  setItemAsync: jest.fn(async () => {}),
}));
describe('amount visibility', () => {
  it('loads the saved privacy preference and persists changes', async () => {
    Platform.OS = 'ios';
    await restoreAmountVisibility();
    expect(amountsHidden()).toBe(true);
    await setAmountsHidden(false);
    expect(amountsHidden()).toBe(false);
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith('trackmywealth.hide-amounts', 'false');
    await setAmountsHidden(true);
  });
});
