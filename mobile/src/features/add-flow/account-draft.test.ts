import {
  accountDraftComplete,
  accountRequest,
  identifierRejected,
  initialAccountDraft,
  withAccountType,
  type AccountDraft,
} from './account-draft';
import type { AccountType } from './model';
import { portfolio } from './test-fixtures';

const typeName = (type: AccountType) => `name:${type}`;
const draft = (patch: Partial<AccountDraft> = {}, base = portfolio): AccountDraft => ({
  ...initialAccountDraft(base, typeName),
  ...patch,
});

describe('S6 account draft (#491)', () => {
  it('starts as a current account in the portfolio currency, without a pension scheme in DE', () => {
    expect(initialAccountDraft(portfolio, typeName)).toMatchObject({
      accountType: 'CASH',
      name: 'name:CASH',
      currency: 'EUR',
      identifierKind: 'IBAN',
      pensionScheme: null,
      billingCurrency: null,
    });
    expect(initialAccountDraft({ ...portfolio, country: 'CH' }, typeName).pensionScheme).toBe(
      'CH_PILLAR_3A',
    );
  });

  it('follows the chip with name and identifier kind until the name is edited', () => {
    const card = withAccountType(draft(), 'CREDIT_CARD', typeName);
    expect(card).toMatchObject({ name: 'name:CREDIT_CARD', identifierKind: 'MASKED_CARD' });
    const edited = withAccountType(
      draft({ name: 'Tagesgeld', nameEdited: true }),
      'SAVINGS',
      typeName,
    );
    expect(edited.name).toBe('Tagesgeld');
  });

  it('requires a pension scheme, a principal and valid amounts before creating', () => {
    expect(accountDraftComplete(draft())).toBe(true);
    expect(accountDraftComplete(draft({ name: ' ' }))).toBe(false);
    expect(accountDraftComplete(draft({ accountType: 'PENSION' }))).toBe(false);
    expect(
      accountDraftComplete(draft({ accountType: 'PENSION', pensionScheme: 'DE_RIESTER' })),
    ).toBe(true);
    expect(accountDraftComplete(draft({ accountType: 'MORTGAGE' }))).toBe(false);
    expect(accountDraftComplete(draft({ accountType: 'MORTGAGE', principal: '1000' }))).toBe(true);
    expect(
      accountDraftComplete(
        draft({ accountType: 'MORTGAGE', principal: '1000', interestInvalid: true }),
      ),
    ).toBe(false);
  });

  it('blocks an unmasked partial card number but not an empty optional identifier', () => {
    const card = withAccountType(draft(), 'CREDIT_CARD', typeName);
    expect(identifierRejected(card)).toBe(false);
    expect(identifierRejected({ ...card, identifier: '4242 42' })).toBe(true);
    expect(accountDraftComplete({ ...card, identifier: '4242 42' })).toBe(false);
    expect(accountDraftComplete({ ...card, identifier: '4242 4242 4242 4242' })).toBe(true);
  });

  it('sends only the chosen type fields, never the identifier', () => {
    expect(accountRequest('p-1', draft({ identifier: 'DE89 3704' }))).toEqual({
      portfolioId: 'p-1',
      name: 'name:CASH',
      accountType: 'CASH',
      nativeCurrency: 'EUR',
    });
    expect(
      accountRequest(
        'p-1',
        draft({ accountType: 'SAVINGS', countsAsSaving: false, principal: '5' }),
      ),
    ).toEqual(expect.objectContaining({ accountType: 'SAVINGS', countsAsSaving: false }));
    expect(
      accountRequest('p-1', draft({ accountType: 'SAVINGS', principal: '5' })),
    ).not.toHaveProperty('originalPrincipal');
    expect(
      accountRequest('p-1', draft({ accountType: 'LOAN', principal: '9000.50', interest: '1.25' })),
    ).toEqual(
      expect.objectContaining({ originalPrincipal: '9000.50', interestRatePercent: '1.25' }),
    );
  });

  it('leaves the billing currency to the server unless it differs from the account currency', () => {
    const card = draft({ accountType: 'CREDIT_CARD', currency: 'USD' });
    expect(accountRequest('p-1', card)).not.toHaveProperty('billingCurrency');
    expect(accountRequest('p-1', { ...card, billingCurrency: 'USD' })).not.toHaveProperty(
      'billingCurrency',
    );
    expect(accountRequest('p-1', { ...card, billingCurrency: 'CHF' })).toEqual(
      expect.objectContaining({ nativeCurrency: 'USD', billingCurrency: 'CHF' }),
    );
    expect(
      accountRequest('p-1', draft({ accountType: 'CASH', billingCurrency: 'CHF' })),
    ).not.toHaveProperty('billingCurrency');
  });
});
