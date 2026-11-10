import { act, fireEvent, screen, waitFor } from '@testing-library/react-native';

import { showToast } from '@/features/shell/toast';
import { useOnline } from '@/hooks/use-online';
import type { AppLocale } from '@/i18n';
import { renderWithIntl } from '@/i18n/test-utils';

import { AccountForm } from './account-form';
import type { Account, Portfolio } from './model';
import { createdAccount, portfolio, problem } from './test-fixtures';
import {
  useCreateAccount,
  useRecordOpeningBalance,
  useRecordValuation,
  useSaveIdentifier,
} from './use-create';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => jest.requireActual('./test-fixtures').theme,
}));
jest.mock('./use-create', () => ({
  useCreateAccount: jest.fn(),
  useSaveIdentifier: jest.fn(),
  useRecordOpeningBalance: jest.fn(),
  useRecordValuation: jest.fn(),
}));
jest.mock('@/hooks/use-online', () => ({ useOnline: jest.fn(() => true) }));
jest.mock('@/features/shell/toast', () => ({ showToast: jest.fn() }));

type MutationMock = {
  mutateAsync: jest.Mock;
  isPending: boolean;
  isError: boolean;
  error: unknown;
  reset: jest.Mock;
};
const mutation = (): MutationMock => ({
  mutateAsync: jest.fn(),
  isPending: false,
  isError: false,
  error: null,
  reset: jest.fn(),
});
let create: MutationMock;
let identifier: MutationMock;
let opening: MutationMock;
let valuation: MutationMock;

/** A mutation that has failed: what the hook reports after `mutateAsync` rejected. */
function failed(mock: MutationMock, error: unknown) {
  mock.mutateAsync.mockRejectedValue(error);
  mock.isError = true;
  mock.error = error;
}

async function mount(p: Portfolio = portfolio, locale: AppLocale = 'en') {
  const props = {
    onFinished: jest.fn(),
    onCancel: jest.fn(),
    onImport: jest.fn(),
    onPortfolioGone: jest.fn(),
  };
  await renderWithIntl(<AccountForm portfolio={p} {...props} />, locale);
  return props;
}
const addButton = () => screen.getByRole('button', { name: 'Add account' });
const startTitle = () => screen.findByText('Where does the value start?');

beforeEach(() => {
  jest.clearAllMocks();
  create = mutation();
  identifier = mutation();
  opening = mutation();
  valuation = mutation();
  create.mutateAsync.mockResolvedValue(createdAccount());
  identifier.mutateAsync.mockResolvedValue({ accountId: 'a-1', version: 4 });
  opening.mutateAsync.mockResolvedValue({});
  valuation.mutateAsync.mockResolvedValue({});
  jest.mocked(useOnline).mockReturnValue(true);
  jest.mocked(useCreateAccount).mockImplementation(() => create as never);
  jest.mocked(useSaveIdentifier).mockImplementation(() => identifier as never);
  jest.mocked(useRecordOpeningBalance).mockImplementation(() => opening as never);
  jest.mocked(useRecordValuation).mockImplementation(() => valuation as never);
});

describe('Add account S6 (#491): step 1', () => {
  it('sends CH pillar 3a in the portfolio currency for a CH portfolio', async () => {
    await mount({ ...portfolio, country: 'CH', portfolioCurrency: 'CHF' });
    await fireEvent.press(screen.getByRole('radio', { name: 'Pillar 3a / pension' }));
    expect(screen.getByRole('radio', { name: 'Swiss pillar 3a' })).toBeChecked();
    await fireEvent.press(addButton());
    expect(create.mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({
        portfolioId: 'p-1',
        accountType: 'PENSION',
        pensionScheme: 'CH_PILLAR_3A',
        nativeCurrency: 'CHF',
      }),
    );
  });

  it('assumes no pension scheme outside CH: the member must choose one', async () => {
    await mount();
    await fireEvent.press(screen.getByRole('radio', { name: 'Pillar 3a / pension' }));
    expect(screen.getByText('Required.')).toBeTruthy();
    expect(addButton()).toBeDisabled();
    await fireEvent.press(screen.getByRole('radio', { name: 'Riester pension' }));
    await fireEvent.press(addButton());
    expect(create.mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ accountType: 'PENSION', pensionScheme: 'DE_RIESTER' }),
    );
  });

  it('requires principal for a mortgage and has no step 2 then', async () => {
    create.mutateAsync.mockResolvedValue(
      createdAccount({ accountType: 'MORTGAGE', hasAmortisation: true }),
    );
    const { onFinished } = await mount();
    await fireEvent.press(screen.getByRole('radio', { name: 'Mortgage' }));
    expect(addButton()).toBeDisabled();
    await fireEvent.changeText(screen.getByLabelText('Original principal'), '125000.75');
    await fireEvent.press(addButton());
    expect(create.mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ accountType: 'MORTGAGE', originalPrincipal: '125000.75' }),
    );
    await waitFor(() => expect(onFinished).toHaveBeenCalledTimes(1));
    expect(showToast).toHaveBeenCalledWith('addAccount.added');
    expect(opening.mutateAsync).not.toHaveBeenCalled();
    expect(valuation.mutateAsync).not.toHaveBeenCalled();
  });

  it('sends whether a savings account counts as saving', async () => {
    await mount();
    await fireEvent.press(screen.getByRole('radio', { name: 'Savings account' }));
    await fireEvent(screen.getByLabelText('Counts as saving'), 'valueChange', false);
    await fireEvent.press(addButton());
    expect(create.mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ accountType: 'SAVINGS', countsAsSaving: false }),
    );
  });

  it('links the type warning to both chip groups', async () => {
    await mount();
    const warning =
      'The account type cannot be changed later. To change it, close this account and add a new one.';
    expect(screen.getByRole('radio', { name: 'Current account' }).props.accessibilityHint).toBe(
      warning,
    );
    expect(screen.getByRole('radio', { name: 'Loan' }).props.accessibilityHint).toBe(warning);
  });

  it('sends one POST for a double tap', async () => {
    let resolve: (account: Account) => void = () => undefined;
    create.mutateAsync.mockReturnValue(new Promise<Account>((done) => (resolve = done)));
    await mount();
    await fireEvent.press(addButton());
    await fireEvent.press(addButton());
    expect(create.mutateAsync).toHaveBeenCalledTimes(1);
    await act(async () => resolve(createdAccount()));
    expect(await startTitle()).toBeTruthy();
  });

  it('does not create accounts offline and says why', async () => {
    jest.mocked(useOnline).mockReturnValue(false);
    await mount();
    expect(screen.getByText("You're offline. Connect to save your changes.")).toBeTruthy();
    await fireEvent.press(addButton());
    expect(create.mutateAsync).not.toHaveBeenCalled();
  });

  it('marks the fields a VALIDATION_FAILED names, keeping the input', async () => {
    failed(
      create,
      problem(400, 'VALIDATION_FAILED', {
        errors: [
          { field: 'name', message: 'raw' },
          { field: 'nativeCurrency', message: 'raw' },
        ],
      }),
    );
    await mount();
    expect(screen.getAllByText('Please check this field.')).toHaveLength(2);
    expect(screen.queryByText('raw')).toBeNull();
    expect(screen.getByLabelText('Account name').props.value).toBe('Current account');
  });

  it('leaves to S1 when the portfolio was removed meanwhile', async () => {
    failed(create, problem(404, 'NOT_FOUND'));
    const { onPortfolioGone, onFinished } = await mount();
    await fireEvent.press(addButton());
    await waitFor(() => expect(onPortfolioGone).toHaveBeenCalledTimes(1));
    expect(onFinished).not.toHaveBeenCalled();
  });

  it('renders S6 in German', async () => {
    await mount(portfolio, 'de');
    // M17: the heading names the portfolio; "Konto hinzufügen" is the screen title only.
    expect(screen.getByRole('header', { name: 'Füge ein Konto zu DKB hinzu' })).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'Privatkonto' })).toBeChecked();
    expect(screen.getByRole('button', { name: 'Konto hinzufügen' })).toBeTruthy();
    expect(screen.getByLabelText('Kontoname').props.value).toBe('Privatkonto');
  });
});

describe('Add account S6 (#491): identifier', () => {
  it('saves the IBAN compact after create and never shows the failure text on success', async () => {
    let resolve: (value: unknown) => void = () => undefined;
    identifier.mutateAsync.mockReturnValue(new Promise((done) => (resolve = done)));
    await mount();
    await fireEvent.changeText(screen.getByLabelText('IBAN'), 'de89 3704 0044 0532 0130 00');
    expect(screen.getByLabelText('IBAN').props.value).toBe('DE89 3704 0044 0532 0130 00');
    await fireEvent.press(addButton());
    expect(identifier.mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({
        account: expect.objectContaining({ id: 'a-1', version: 3 }),
        identifier: 'DE89370400440532013000',
        kind: 'IBAN',
      }),
    );
    expect(screen.queryByText(/could not be saved/)).toBeNull();
    await act(async () => resolve({}));
    expect(await startTitle()).toBeTruthy();
    expect(screen.queryByText(/could not be saved/)).toBeNull();
  });

  it('keeps the account when the IBAN is invalid and offers Save IBAN and Skip', async () => {
    failed(identifier, problem(422, 'ACCOUNT_IDENTIFIER_INVALID', { field: 'identifier' }));
    const { onFinished } = await mount();
    await fireEvent.changeText(screen.getByLabelText('IBAN'), 'INVALID');
    await fireEvent.press(addButton());
    expect(await screen.findByText(/The account was created/)).toBeTruthy();
    expect(screen.getByText('This isn’t a valid IBAN. Check it for typing errors.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Save IBAN' })).toBeTruthy();
    expect(screen.getByText('Step 1 of 2')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Skip for now' }));
    expect(await startTitle()).toBeTruthy();
    expect(onFinished).not.toHaveBeenCalled();
    expect(create.mutateAsync).toHaveBeenCalledTimes(1);
  });

  it('says when the IBAN already belongs to another account, and retries on Save', async () => {
    failed(identifier, problem(409, 'ACCOUNT_IDENTIFIER_IN_USE'));
    await mount();
    await fireEvent.changeText(screen.getByLabelText('IBAN'), 'CH9300762011623852957');
    await fireEvent.press(addButton());
    expect(
      await screen.findByText('This identifier already belongs to another account.'),
    ).toBeTruthy();
    identifier.mutateAsync.mockResolvedValue({});
    await fireEvent.press(screen.getByRole('button', { name: 'Save IBAN' }));
    expect(await startTitle()).toBeTruthy();
    expect(identifier.mutateAsync).toHaveBeenCalledTimes(2);
    expect(create.mutateAsync).toHaveBeenCalledTimes(1);
  });

  it('masks a credit card number on the device and leaves billing to the account currency', async () => {
    create.mutateAsync.mockResolvedValue(
      createdAccount({ accountType: 'CREDIT_CARD', nature: 'LIABILITY' }),
    );
    await mount();
    await fireEvent.press(screen.getByRole('radio', { name: 'Credit card' }));
    const card = screen.getByLabelText('Card number');
    await fireEvent.changeText(card, '4242 42');
    expect(addButton()).toBeDisabled();
    await fireEvent.changeText(card, '4242 4242 4242 4242');
    await fireEvent(card, 'blur');
    expect(screen.getByLabelText('Card number').props.value).toBe('4242 42•• •••• 4242');
    await fireEvent.press(screen.getByRole('button', { name: /^Account currency/ }));
    await fireEvent.press(screen.getByRole('radio', { name: 'USD · US Dollar' }));
    await fireEvent.press(addButton());
    const body = create.mutateAsync.mock.calls[0][0];
    expect(body).toEqual(
      expect.objectContaining({ accountType: 'CREDIT_CARD', nativeCurrency: 'USD' }),
    );
    expect(body).not.toHaveProperty('billingCurrency');
    expect(JSON.stringify(body)).not.toContain('4242');
    await waitFor(() =>
      expect(identifier.mutateAsync).toHaveBeenCalledWith(
        expect.objectContaining({ identifier: '424242••••••4242', kind: 'MASKED_CARD' }),
      ),
    );
  });
});

describe('Add account S6 (#491): step 2', () => {
  it('records a decimal-string opening balance, then closes with a toast', async () => {
    const { onFinished } = await mount();
    await fireEvent.press(addButton());
    expect(await startTitle()).toBeTruthy();
    expect(screen.getByText('Step 2 of 2')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Opening balance'), '1000.50');
    await fireEvent.press(screen.getByRole('button', { name: 'Save starting value' }));
    await waitFor(() =>
      expect(opening.mutateAsync).toHaveBeenCalledWith(
        expect.objectContaining({
          account: expect.objectContaining({ id: 'a-1' }),
          balance: '1000.50',
        }),
      ),
    );
    expect(onFinished).toHaveBeenCalledTimes(1);
    expect(showToast).toHaveBeenCalledWith('addAccount.added');
  });

  it('posts a valuation for a custom asset instead of an opening balance', async () => {
    create.mutateAsync.mockResolvedValue(
      createdAccount({
        accountType: 'CUSTOM_ASSET',
        manualValuation: true,
        hasTransactions: false,
      }),
    );
    const { onFinished } = await mount();
    await fireEvent.press(screen.getByRole('radio', { name: 'Property & other' }));
    await fireEvent.press(addButton());
    expect(create.mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ accountType: 'CUSTOM_ASSET', customAssetType: 'REAL_ESTATE' }),
    );
    expect(await startTitle()).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Import a statement instead' })).toBeNull();
    await fireEvent.changeText(screen.getByLabelText('Current value'), '800000.45');
    await fireEvent.press(screen.getByRole('button', { name: 'Save starting value' }));
    await waitFor(() =>
      expect(valuation.mutateAsync).toHaveBeenCalledWith(
        expect.objectContaining({ value: '800000.45' }),
      ),
    );
    expect(opening.mutateAsync).not.toHaveBeenCalled();
    expect(onFinished).toHaveBeenCalledTimes(1);
  });

  it('skips silently when the server says an opening balance does not apply', async () => {
    failed(opening, problem(422, 'OPENING_BALANCE_NOT_APPLICABLE'));
    const { onFinished } = await mount();
    await fireEvent.press(addButton());
    await startTitle();
    await fireEvent.changeText(screen.getByLabelText('Opening balance'), '10');
    await fireEvent.press(screen.getByRole('button', { name: 'Save starting value' }));
    await waitFor(() => expect(onFinished).toHaveBeenCalledTimes(1));
  });

  it('skips without posting zero, and offers an import instead', async () => {
    const { onFinished, onImport } = await mount();
    await fireEvent.press(addButton());
    await startTitle();
    await fireEvent.press(screen.getByRole('button', { name: 'Import a statement instead' }));
    expect(onImport).toHaveBeenCalledWith('a-1');
    await fireEvent.press(screen.getByRole('button', { name: 'Skip for now' }));
    expect(onFinished).toHaveBeenCalledTimes(1);
    expect(opening.mutateAsync).not.toHaveBeenCalled();
    expect(showToast).toHaveBeenCalledWith('addAccount.added');
  });
});
