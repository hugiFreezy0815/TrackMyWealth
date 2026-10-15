import { api } from '@/api/client';

/**
 * US-07-11: what a statement suggests for an account that does not exist yet
 * (`ImportAccountProposalResponse`). Advisory only; any field is null when the file does not tell.
 */
export type AccountProposal = {
  templateId: string;
  /** The template's institution catalogue entry, not one of the workspace's institutions. */
  institutionCatalogueId: string | null;
  institutionName: string | null;
  suggestedAccountType: string | null;
  suggestedCurrency: string | null;
  /** The identifier's display value; the identifier itself never reaches the client. */
  maskedIdentifier: string | null;
};

/** The account types `POST /api/v1/accounts` accepts (backend `AccountTypeValues.PATTERN`). */
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
export type ConfirmedAccountDraft = {
  financialInstitutionId: string;
  name: string;
  accountType: AccountType;
  nativeCurrency: string;
};

export function isAccountType(value: string): value is AccountType {
  return (ACCOUNT_TYPES as readonly string[]).includes(value);
}

/**
 * The create-account request the member confirmed. The institution is one of the workspace's,
 * chosen by the member: a catalogue entry is not one. The identifier is not part of it; it is
 * registered from the file once the account exists ({@link registerIdentifierFromImport}).
 */
export function prepareAccountDraft(
  selectedInstitutionId: string,
  name: string,
  approvedType: string,
  approvedCurrency: string,
): ConfirmedAccountDraft {
  if (!selectedInstitutionId.trim() || !name.trim()) {
    throw new Error('Choose an institution and enter an account name.');
  }
  const currency = approvedCurrency.trim().toUpperCase();
  if (!isAccountType(approvedType) || !/^[A-Z]{3}$/.test(currency)) {
    throw new Error('Choose an account type and enter a three-letter currency code.');
  }
  return {
    financialInstitutionId: selectedInstitutionId,
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
  return api.post<AccountProposal>(
    `/api/v1/accounts/${accountId}/imports/${batchId}/account-proposal`,
  );
}

/** As `AccountIdentifierResponse`: the registered identifier as far as it is shown. */
export type AccountIdentifier = {
  accountId: string;
  kind: string;
  identifierMasked: string | null;
  version: number;
};

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
  return api.putVersioned<AccountIdentifier>(
    `/api/v1/accounts/${accountId}/identifier/from-import`,
    accountVersion,
    { importAccountId, batchId },
  );
}
