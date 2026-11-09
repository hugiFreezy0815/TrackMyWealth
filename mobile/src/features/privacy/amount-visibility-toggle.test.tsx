import { fireEvent, render, screen } from '@testing-library/react-native';

import { withIntl } from '@/i18n/test-utils';

import { setAmountsHidden } from './amount-visibility';
import { AmountVisibilityToggle } from './amount-visibility-toggle';
import { PrivacyAmount } from './privacy-amount';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => {}),
}));

beforeEach(async () => {
  await setAmountsHidden(false);
});

describe('eye control (FR-CLI-005)', () => {
  it('hides every amount and tells screen readers it is on', async () => {
    await render(
      withIntl(
        <>
          <AmountVisibilityToggle />
          <PrivacyAmount value="1234.50" currency="CHF" />
          <PrivacyAmount value="-20.00" currency="EUR" />
        </>,
      ),
    );
    const toggle = screen.getByRole('switch', { name: 'Hide amounts' });
    expect(toggle).not.toBeChecked();
    expect(screen.getByText(/1'234\.50/)).toBeTruthy();

    await fireEvent.press(toggle);

    expect(screen.getByRole('switch', { name: 'Hide amounts' })).toBeChecked();
    expect(screen.getAllByText('••••')).toHaveLength(2);
    expect(screen.getAllByLabelText('hidden')).toHaveLength(2);
    expect(screen.queryByText(/1'234/)).toBeNull();
    expect(screen.getByText('Show amounts')).toBeTruthy();
  });

  it('shows the amounts again when turned off', async () => {
    await setAmountsHidden(true);
    await render(
      withIntl(
        <>
          <AmountVisibilityToggle />
          <PrivacyAmount value="1234.50" currency="CHF" />
        </>,
      ),
    );

    await fireEvent.press(screen.getByRole('switch', { name: 'Hide amounts' }));

    expect(screen.getByText(/1'234\.50/)).toBeTruthy();
  });

  it('announces hidden amounts in German as "verborgen"', async () => {
    await setAmountsHidden(true);
    await render(withIntl(<PrivacyAmount value="1234.50" currency="CHF" />, 'de'));

    expect(screen.getByLabelText('verborgen')).toBeTruthy();
  });
});
