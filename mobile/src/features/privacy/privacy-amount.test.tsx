import { render, screen } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';
import { PrivacyAmount } from './privacy-amount';
import { useAmountsHidden } from './amount-visibility';

jest.mock('./amount-visibility', () => ({ useAmountsHidden: jest.fn(() => true) }));

it('never exposes a hidden amount to visual users or screen readers', async () => {
  jest.mocked(useAmountsHidden).mockReturnValue(true);
  await render(
    <IntlProvider locale="en">
      <PrivacyAmount value="1234.50" currency="CHF" />
    </IntlProvider>,
  );
  expect(screen.getByText('••••')).toBeTruthy();
  expect(screen.getByLabelText('hidden')).toBeTruthy();
  expect(screen.queryByText(/1,234/)).toBeNull();
});
it('shows the amount when privacy is disabled', async () => {
  jest.mocked(useAmountsHidden).mockReturnValue(false);
  await render(
    <IntlProvider locale="en">
      <PrivacyAmount value="1234.50" currency="CHF" />
    </IntlProvider>,
  );
  expect(screen.getByText(/CHF/)).toBeTruthy();
});
