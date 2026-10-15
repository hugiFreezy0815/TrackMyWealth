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
        institution={{ id: 'institution', name: 'Synthetic Bank Basel' }}
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
        institution={{ id: 'institution', name: 'Synthetic Bank Basel' }}
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
      financialInstitutionId: 'institution',
      name: 'Kreditkarte',
      accountType: 'CASH',
      nativeCurrency: 'EUR',
    });
  });

  it('asks for an institution before anything can be confirmed', async () => {
    const onConfirm = jest.fn();
    await render(
      <AccountProposalReview
        proposal={proposal}
        institution={null}
        onConfirm={onConfirm}
        onCancel={jest.fn()}
      />,
    );

    expect(screen.getByText(/Choose an institution first/)).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Account name'), 'Kreditkarte');
    await fireEvent.press(screen.getByText('Confirm new account details'));
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
