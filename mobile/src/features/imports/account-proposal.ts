import type { components } from '@/api/schema';
import { typedApi } from '@/api/typed-api';

/**
 * US-07-11: what a statement suggests for an account that does not exist yet. Advisory only. The
 * institution is the template's catalogue entry, not one of the workspace's portfolios, and the
 * identifier comes as its display value only: the identifier itself never reaches the client.
 */
export type AccountProposal = components['schemas']['ImportAccountProposalResponse'];

/**
 * The account types `POST /api/v1/accounts` accepts (backend `AccountTypeValues.PATTERN`). The
 * contract types `accountType` as a pattern-checked string, so the list is the app's own.
 */
export const ACCOUNT_TYPES = [
  'CASH',
  'SAVINGS',
  'SECURITIES',
  'MANAGED_MANDATE',
  'PENSION',
  'VESTED_BENEFITS',
  'CREDIT_CARD',
  'MORTGAGE',
  'LOAN',
  'CRYPTO',
  'CUSTOM_ASSET',
] as const;

export type AccountType = (typeof ACCOUNT_TYPES)[number];

/** The fields of `POST /api/v1/accounts` the member has confirmed. */
export type ConfirmedAccountDraft = Required<
  Pick<components['schemas']['CreateAccountRequest'], 'portfolioId' | 'name' | 'nativeCurrency'>
> & { accountType: AccountType };

export function isAccountType(value: string): value is AccountType {
  return (ACCOUNT_TYPES as readonly string[]).includes(value);
}

/**
 * The create-account request the member confirmed. The portfolio is one of the workspace's,
 * chosen by the member: a catalogue entry is not one. The identifier is not part of it; it is
 * registered from the file once the account exists ({@link registerIdentifierFromImport}).
 */
export function prepareAccountDraft(
  selectedPortfolioId: string,
  name: string,
  approvedType: string,
  approvedCurrency: string,
): ConfirmedAccountDraft {
  if (!selectedPortfolioId.trim() || !name.trim()) {
    throw new Error('Choose a portfolio and enter an account name.');
  }
  const currency = approvedCurrency.trim().toUpperCase();
  if (!isAccountType(approvedType) || !/^[A-Z]{3}$/.test(currency)) {
    throw new Error('Choose an account type and enter a three-letter currency code.');
  }
  return {
    portfolioId: selectedPortfolioId,
    name: name.trim(),
    accountType: approvedType,
    nativeCurrency: currency,
  };
}

/** The proposal for an open import batch whose file names no account of the workspace. */
export function proposeAccountFromBatch(
  accountId: string,
  batchId: string,
): Promise<AccountProposal> {
  return typedApi.post('/api/v1/accounts/{accountId}/imports/{batchId}/account-proposal', {
    params: { accountId, batchId },
  });
}

/** The registered identifier as far as it is shown. */
export type AccountIdentifier = components['schemas']['AccountIdentifierResponse'];

/**
 * Registers on the new account the identifier the batch's file names, read on the server: the
 * member does not type it, and it never travels.
 */
export function registerIdentifierFromImport(
  accountId: string,
  accountVersion: number,
  importAccountId: string,
  batchId: string,
): Promise<AccountIdentifier> {
  return typedApi.put('/api/v1/accounts/{accountId}/identifier/from-import', {
    params: { accountId },
    version: accountVersion,
    body: { importAccountId, batchId },
  });
}
