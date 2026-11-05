import { fireEvent, render, screen } from '@testing-library/react-native';

import type { AccountProposal } from './account-proposal';
import { AccountProposalReview } from './account-proposal-review';

jest.mock('@/global.css', () => ({}));

const proposal: AccountProposal = {
  templateId: 'template',
  institutionCatalogueId: 'catalogue',
  institutionName: 'Synthetic Bank',
  suggestedAccountType: 'CREDIT_CARD',
  suggestedCurrency: 'EUR',
  maskedIdentifier: '123456******1234',
};

describe('AccountProposalReview', () => {
  it('shows names, not ids, and preselects the suggested type', async () => {
    await render(
      <AccountProposalReview
        proposal={proposal}
        portfolio={{ id: 'portfolio', name: 'Synthetic Bank Basel' }}
        onConfirm={jest.fn()}
        onCancel={jest.fn()}
      />,
    );

    expect(screen.getByText(/Synthetic Bank Basel/)).toBeTruthy();
    expect(screen.queryByText(/catalogue/)).toBeNull();
    expect(screen.getByText('123456******1234', { exact: false })).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'CREDIT_CARD', selected: true })).toBeTruthy();
  });

  it('creates nothing until the member confirms, then hands over the confirmed draft', async () => {
    const onConfirm = jest.fn();
    await render(
      <AccountProposalReview
        proposal={proposal}
        portfolio={{ id: 'portfolio', name: 'Synthetic Bank Basel' }}
        onConfirm={onConfirm}
        onCancel={jest.fn()}
      />,
    );

    await fireEvent.press(screen.getByText('Confirm new account details'));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeTruthy();

    await fireEvent.changeText(screen.getByLabelText('Account name'), 'Kreditkarte');
    await fireEvent.press(screen.getByRole('radio', { name: 'CASH' }));
    await fireEvent.press(screen.getByText('Confirm new account details'));

    expect(onConfirm).toHaveBeenCalledWith({
      portfolioId: 'portfolio',
      name: 'Kreditkarte',
      accountType: 'CASH',
      nativeCurrency: 'EUR',
    });
  });

  it('asks for the currency when the statement suggests none and names no identifier (#460)', async () => {
    const onConfirm = jest.fn();
    await render(
      <AccountProposalReview
        proposal={{ ...proposal, suggestedCurrency: null, maskedIdentifier: null }}
        portfolio={{ id: 'portfolio', name: 'Synthetic Bank Basel' }}
        onConfirm={onConfirm}
        onCancel={jest.fn()}
      />,
    );

    expect(screen.getByText('Account identifier: Not found')).toBeTruthy();
    expect(screen.getByLabelText('Currency').props.value).toBe('');
    await fireEvent.changeText(screen.getByLabelText('Account name'), 'Kreditkarte');
    await fireEvent.press(screen.getByText('Confirm new account details'));
    expect(onConfirm).not.toHaveBeenCalled();

    await fireEvent.changeText(screen.getByLabelText('Currency'), 'chf');
    await fireEvent.press(screen.getByText('Confirm new account details'));
    expect(onConfirm).toHaveBeenCalledWith(expect.objectContaining({ nativeCurrency: 'CHF' }));
  });

  it('keeps the form usable when a newer server suggests an unknown account type', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    const onConfirm = jest.fn();
    try {
      await render(
        <AccountProposalReview
          proposal={{ ...proposal, suggestedAccountType: 'ART' }}
          portfolio={{ id: 'portfolio', name: 'Synthetic Bank Basel' }}
          onConfirm={onConfirm}
          onCancel={jest.fn()}
        />,
      );

      expect(screen.getByText('Suggested account type: Other')).toBeTruthy();
      expect(screen.getByText('Portfolio: Synthetic Bank Basel')).toBeTruthy();
      expect(screen.getByText('Account identifier: 123456******1234')).toBeTruthy();
      expect(screen.queryByRole('radio', { selected: true })).toBeNull();

      await fireEvent.changeText(screen.getByLabelText('Account name'), 'Art account');
      await fireEvent.press(screen.getByText('Confirm new account details'));
      expect(onConfirm).not.toHaveBeenCalled();
      expect(screen.getByRole('alert')).toBeTruthy();

      await fireEvent.press(screen.getByRole('radio', { name: 'CASH' }));
      await fireEvent.press(screen.getByText('Confirm new account details'));
      expect(onConfirm).toHaveBeenCalledWith(
        expect.objectContaining({ accountType: 'CASH', name: 'Art account' }),
      );
    } finally {
      warn.mockRestore();
    }
  });

  it('asks for a portfolio before anything can be confirmed', async () => {
    const onConfirm = jest.fn();
    await render(
      <AccountProposalReview
        proposal={proposal}
        portfolio={null}
        onConfirm={onConfirm}
        onCancel={jest.fn()}
      />,
    );

    expect(screen.getByText(/Choose a portfolio first/)).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Account name'), 'Kreditkarte');
    await fireEvent.press(screen.getByText('Confirm new account details'));
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
