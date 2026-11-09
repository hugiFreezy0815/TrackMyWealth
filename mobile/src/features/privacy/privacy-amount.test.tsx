import { render, screen } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';
import { PrivacyAmount } from './privacy-amount';
import { useAmountsHidden } from './amount-visibility';

jest.mock('./amount-visibility', () => ({ useAmountsHidden: jest.fn(() => true) }));

it('never exposes a hidden amount to visual users or screen readers', () => {
  jest.mocked(useAmountsHidden).mockReturnValue(true);
  render(
    <IntlProvider locale="en">
      <PrivacyAmount value="1234.50" currency="CHF" />
    </IntlProvider>,
  );
  expect(screen.getByText('••••')).toBeTruthy();
  expect(screen.getByLabelText('hidden')).toBeTruthy();
  expect(screen.queryByText(/1,234/)).toBeNull();
});
it('shows the amount when privacy is disabled', () => {
  jest.mocked(useAmountsHidden).mockReturnValue(false);
  render(
    <IntlProvider locale="en">
      <PrivacyAmount value="1234.50" currency="CHF" />
    </IntlProvider>,
  );
  expect(screen.getByText(/CHF/)).toBeTruthy();
});
