import type { DecimalString } from '@/api/decimal';

import {
  ACCOUNT_TYPES,
  defaultPensionScheme,
  identifierKind,
  identifierToSend,
  needsIdentifier,
  needsPrincipal,
  validAccountName,
  type AccountRequest,
  type AccountType,
  type IdentifierKind,
  type PensionScheme,
  type Portfolio,
} from './model';

/** What the member has entered on S6 step 1, before anything is sent. */
export type AccountDraft = {
  accountType: AccountType;
  name: string;
  /** Once edited, choosing another type no longer replaces the name with the type's default. */
  nameEdited: boolean;
  currency: string;
  openedAt: string | null;
  identifier: string;
  identifierKind: IdentifierKind;
  pensionScheme: PensionScheme | null;
  customAssetType: string;
  countsAsSaving: boolean;
  /** Null: the card is billed in the account currency (the server's default). */
  billingCurrency: string | null;
  principal: DecimalString | null;
  principalInvalid: boolean;
  interest: DecimalString | null;
  interestInvalid: boolean;
};

export function typeNameKey(type: AccountType): string {
  return ACCOUNT_TYPES.find((item) => item.type === type)?.key ?? 'addAccount.type.CASH';
}

export function initialAccountDraft(
  portfolio: Portfolio,
  typeName: (type: AccountType) => string,
): AccountDraft {
  return {
    accountType: 'CASH',
    name: typeName('CASH'),
    nameEdited: false,
    currency: portfolio.portfolioCurrency,
    openedAt: null,
    identifier: '',
    identifierKind: identifierKind('CASH'),
    pensionScheme: defaultPensionScheme(portfolio.country),
    customAssetType: 'REAL_ESTATE',
    countsAsSaving: true,
    billingCurrency: null,
    principal: null,
    principalInvalid: false,
    interest: null,
    interestInvalid: false,
  };
}

/** Choosing a chip: the type's default name (unless edited) and identifier kind. */
export function withAccountType(
  draft: AccountDraft,
  type: AccountType,
  typeName: (type: AccountType) => string,
): AccountDraft {
  return {
    ...draft,
    accountType: type,
    name: draft.nameEdited ? draft.name : typeName(type),
    identifierKind: identifierKind(type),
  };
}

/** True when the optional identifier was typed but can't be sent (a card number left unmasked). */
export function identifierRejected(draft: AccountDraft): boolean {
  return (
    needsIdentifier(draft.accountType) &&
    draft.identifier.trim() !== '' &&
    identifierToSend(draft.identifier, draft.identifierKind) === null
  );
}

/** Every rule S6 checks before "Add account" is enabled; the server checks them again. */
export function accountDraftComplete(draft: AccountDraft): boolean {
  const type = draft.accountType;
  return (
    validAccountName(draft.name) &&
    /^[A-Z]{3}$/.test(draft.currency) &&
    (type !== 'PENSION' || draft.pensionScheme !== null) &&
    (!needsPrincipal(type) || (draft.principal !== null && !draft.principalInvalid)) &&
    !draft.interestInvalid &&
    !identifierRejected(draft)
  );
}

/**
 * The `POST /accounts` body: only the fields of the chosen type. The identifier is never part of
 * it (its own resource, US-07-10), and a billing currency equal to the account currency is left
 * to the server's default.
 */
export function accountRequest(portfolioId: string, draft: AccountDraft): AccountRequest {
  const type = draft.accountType;
  const billing = draft.billingCurrency;
  return {
    portfolioId,
    name: draft.name.trim(),
    accountType: type,
    nativeCurrency: draft.currency,
    ...(draft.openedAt ? { openedAt: draft.openedAt } : {}),
    ...(type === 'SAVINGS' ? { countsAsSaving: draft.countsAsSaving } : {}),
    ...(type === 'PENSION' && draft.pensionScheme ? { pensionScheme: draft.pensionScheme } : {}),
    ...(type === 'CUSTOM_ASSET' ? { customAssetType: draft.customAssetType } : {}),
    ...(type === 'CREDIT_CARD' && billing && billing !== draft.currency
      ? { billingCurrency: billing }
      : {}),
    ...(needsPrincipal(type) && draft.principal !== null
      ? { originalPrincipal: draft.principal }
      : {}),
    ...(needsPrincipal(type) && draft.interest !== null
      ? { interestRatePercent: draft.interest }
      : {}),
  };
}
