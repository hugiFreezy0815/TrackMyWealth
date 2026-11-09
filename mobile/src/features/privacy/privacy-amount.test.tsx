import { render, screen } from '@testing-library/react-native';
import { withIntl } from '@/i18n/test-utils';
import { PrivacyAmount } from './privacy-amount';
import { useAmountsHidden } from './amount-visibility';

// PrivacyAmount renders the shared Amount, whose theme imports the web stylesheet.
jest.mock('@/global.css', () => ({}));
jest.mock('./amount-visibility', () => ({ useAmountsHidden: jest.fn(() => true) }));

it('never exposes a hidden amount to visual users or screen readers', async () => {
  jest.mocked(useAmountsHidden).mockReturnValue(true);
  await render(withIntl(<PrivacyAmount value="1234.50" currency="CHF" />));
  expect(screen.getByText('••••')).toBeTruthy();
  expect(screen.getByLabelText('hidden')).toBeTruthy();
  expect(screen.queryByText(/1,234/)).toBeNull();
});
it('shows the amount when privacy is disabled', async () => {
  jest.mocked(useAmountsHidden).mockReturnValue(false);
  await render(withIntl(<PrivacyAmount value="1234.50" currency="CHF" />));
  expect(screen.getByText(/CHF/)).toBeTruthy();
});
