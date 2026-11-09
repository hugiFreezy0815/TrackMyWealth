import { ApiError } from '@/api/client';
import { labelFor, type DisplayEnum } from '@/api/enum-label';
import { problemOf, problemReaction } from '@/api/problems';
import type { components } from '@/api/schema';

export type Portfolio = components['schemas']['PortfolioResponse'];
export type Institution = components['schemas']['InstitutionCatalogueEntrySummaryResponse'];
export type Account = components['schemas']['AccountSummaryResponse'];
export type AccountRequest = components['schemas']['CreateAccountRequest'];
export type PortfolioRequest = components['schemas']['CreatePortfolioRequest'];
export type ImportFormat = Institution['importFormats'][number];

export const ACCOUNT_TYPES = [
  { type: 'CASH', key: 'addAccount.type.CASH', group: 'ASSET' },
  { type: 'SAVINGS', key: 'addAccount.type.SAVINGS', group: 'ASSET' },
  { type: 'SECURITIES', key: 'addAccount.type.SECURITIES', group: 'ASSET' },
  { type: 'PENSION', key: 'addAccount.type.PENSION', group: 'ASSET' },
  { type: 'VESTED_BENEFITS', key: 'addAccount.type.VESTED_BENEFITS', group: 'ASSET' },
  { type: 'MANAGED_MANDATE', key: 'addAccount.type.MANAGED_MANDATE', group: 'ASSET' },
  { type: 'CRYPTO', key: 'addAccount.type.CRYPTO', group: 'ASSET' },
  { type: 'CUSTOM_ASSET', key: 'addAccount.type.CUSTOM_ASSET', group: 'ASSET' },
  { type: 'CREDIT_CARD', key: 'addAccount.type.CREDIT_CARD', group: 'LIABILITY' },
  { type: 'MORTGAGE', key: 'addAccount.type.MORTGAGE', group: 'LIABILITY' },
  { type: 'LOAN', key: 'addAccount.type.LOAN', group: 'LIABILITY' },
] as const;

export type AccountType = (typeof ACCOUNT_TYPES)[number]['type'];
export type AccountGroup = (typeof ACCOUNT_TYPES)[number]['group'];
export type StartStep = 'OPENING_BALANCE' | 'VALUATION' | 'NONE';

/** The country chips of M14 ("All" is no filter). The backend filters by any ISO-2 code. */
export const CATALOGUE_COUNTRIES = ['CH', 'DE'] as const;

export const INSTITUTION_TYPES = [
  'BANK',
  'BROKER',
  'PENSION_PROVIDER',
  'PENSION_FUND',
  'ASSET_MANAGER',
  'CARD_ISSUER',
  'CRYPTO_EXCHANGE',
  'INSURER',
  'PLATFORM',
  'OTHER',
] as const;
export type InstitutionType = (typeof INSTITUTION_TYPES)[number];

const INSTITUTION_TYPE_LABELS: Readonly<Record<InstitutionType, string>> = {
  BANK: 'institutionType.BANK',
  BROKER: 'institutionType.BROKER',
  PENSION_PROVIDER: 'institutionType.PENSION_PROVIDER',
  PENSION_FUND: 'institutionType.PENSION_FUND',
  ASSET_MANAGER: 'institutionType.ASSET_MANAGER',
  CARD_ISSUER: 'institutionType.CARD_ISSUER',
  CRYPTO_EXCHANGE: 'institutionType.CRYPTO_EXCHANGE',
  INSURER: 'institutionType.INSURER',
  PLATFORM: 'institutionType.PLATFORM',
  OTHER: 'institutionType.OTHER',
};

/** The message id of an institution type; a type a newer backend added reads "Other" (#452). */
export function institutionTypeKey(type: DisplayEnum<InstitutionType>): string {
  return labelFor(INSTITUTION_TYPE_LABELS, type, 'institutionType.OTHER');
}

const IMPORT_FORMAT_LABELS: Readonly<Record<ImportFormat, string>> = {
  CSV: 'importFormat.CSV',
  PDF: 'importFormat.PDF',
  CAMT: 'importFormat.CAMT',
  SPREADSHEET: 'importFormat.SPREADSHEET',
};

/** The message id of an import format; one this app version doesn't know reads "Other" (#452). */
export function importFormatKey(format: DisplayEnum<ImportFormat>): string {
  return labelFor(IMPORT_FORMAT_LABELS, format, 'importFormat.OTHER');
}

export const PENSION_SCHEMES = [
  'CH_PILLAR_3A',
  'DE_RIESTER',
  'DE_RUERUP',
  'DE_BAV',
  'OTHER',
] as const;
export type PensionScheme = (typeof PENSION_SCHEMES)[number];

export const CUSTOM_ASSET_TYPES = [
  'REAL_ESTATE',
  'VEHICLE',
  'PRECIOUS_METAL',
  'COLLECTIBLE',
  'OTHER',
] as const;

/**
 * S6: a CH portfolio's pension account is preselected as pillar 3a. Elsewhere the scheme is a
 * required choice the member makes: no German or other scheme is ever assumed for them.
 */
export function defaultPensionScheme(country: string | null | undefined): PensionScheme | null {
  return country === 'CH' ? 'CH_PILLAR_3A' : null;
}

/** S5 currency default: CH → CHF, DE → EUR, otherwise the workspace currency (not a figure). */
export function preferredCurrency(country: string | null, fallback: string): string {
  return country === 'CH' ? 'CHF' : country === 'DE' ? 'EUR' : fallback;
}

export const MAX_PORTFOLIO_NAME_LENGTH = 100;

/**
 * S5: at most 100 characters. Required only without a catalogue entry; with one, a blank name is
 * left out of the request and the server names the portfolio after the entry (FR-INS-001).
 */
export function validPortfolioName(name: string, required: boolean): boolean {
  const length = name.trim().length;
  return length <= MAX_PORTFOLIO_NAME_LENGTH && (!required || length > 0);
}
export function validAccountName(name: string): boolean {
  return name.trim().length > 0;
}

/** The catalogue query key: a new search or country starts again at page 0. */
export function catalogueKey(query: string, country: string | null) {
  return ['catalogue', query.trim(), country ?? ''] as const;
}

/** Step 2 by the created account's capability flags, never by its type (US-05-04). */
export function startingStep(account: Account): StartStep {
  if (account.hasAmortisation) return 'NONE';
  if (account.manualValuation) return 'VALUATION';
  if (account.hasTransactions) return 'OPENING_BALANCE';
  return 'NONE';
}
export function needsPrincipal(type: AccountType): boolean {
  return type === 'MORTGAGE' || type === 'LOAN';
}
export function needsIdentifier(type: AccountType): boolean {
  return type === 'CASH' || type === 'SAVINGS' || type === 'SECURITIES' || type === 'CREDIT_CARD';
}

export type IdentifierKind = 'IBAN' | 'MASKED_CARD' | 'CONTRACT_NUMBER';
export function identifierKind(type: AccountType): IdentifierKind {
  return type === 'CREDIT_CARD' ? 'MASKED_CARD' : 'IBAN';
}

/** The field label of an identifier kind. */
export function identifierLabelKey(kind: IdentifierKind): string {
  return kind === 'MASKED_CARD'
    ? 'addAccount.identifier.card'
    : kind === 'CONTRACT_NUMBER'
      ? 'addAccount.identifier.contract'
      : 'addAccount.identifier.iban';
}

const CARD_MASK = '•';
// The mask characters the server accepts (AccountIdentifierService), plus "x" and "*".
const CARD_MASK_INPUT = /[Xx*#·•]/g;
const MIN_FULL_CARD = 13;
const MAX_CARD = 19;
const MIN_MASKED_CARD = 8;

/**
 * A card number as the server may receive it (PCI DSS): at most the first 6 and the last 4
 * digits visible. A fully typed number is masked here, on the device, so it never leaves it;
 * a number already masked with •, X, *, # or · is kept. Null for anything else.
 */
export function maskCardNumber(input: string): string | null {
  const compact = input.replace(/[\s-]/g, '').replace(CARD_MASK_INPUT, CARD_MASK);
  if (/^\d+$/.test(compact)) {
    if (compact.length < MIN_FULL_CARD || compact.length > MAX_CARD) return null;
    return compact.slice(0, 6) + CARD_MASK.repeat(compact.length - 10) + compact.slice(-4);
  }
  const masked =
    compact.length >= MIN_MASKED_CARD &&
    compact.length <= MAX_CARD &&
    /^\d{0,6}•+\d{4}$/.test(compact);
  return masked ? compact : null;
}

/** Groups of four for display: "4242 42•• •••• 4242". */
export function groupInFours(compact: string): string {
  return compact.replace(/(.{4})(?=.)/g, '$1 ');
}

/** An IBAN as typed, in upper case and groups of four ("DE89 3704 0044 …"); checked by the server. */
export function formatIban(input: string): string {
  return groupInFours(input.replace(/[^0-9A-Za-z]/g, '').toUpperCase());
}

/** The identifier field's text after an edit: an IBAN is grouped in fours while typing. */
export function formatIdentifierInput(input: string, kind: IdentifierKind): string {
  return kind === 'IBAN' ? formatIban(input) : input;
}

/**
 * The identifier field's text when it loses focus: a card number is shown masked, so a fully
 * typed number doesn't stay on screen. Anything else is left for the member to correct.
 */
export function identifierOnBlur(input: string, kind: IdentifierKind): string {
  if (kind !== 'MASKED_CARD') return input;
  const masked = maskCardNumber(input);
  return masked ? groupInFours(masked) : input;
}

/** The value to send, or null when the app can tell it is not acceptable (a card number). */
export function identifierToSend(input: string, kind: IdentifierKind): string | null {
  const trimmed = input.trim();
  if (!trimmed) return null;
  if (kind === 'MASKED_CARD') return maskCardNumber(trimmed);
  if (kind === 'IBAN') return trimmed.replace(/\s/g, '').toUpperCase();
  return trimmed;
}

/**
 * The fields a VALIDATION_FAILED (or another 4xx naming a field) rejects, each with the message
 * id to show. The server's own wording is never shown (spec README §8).
 */
export function validationErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError) || error.status < 400 || error.status >= 500) return {};
  const body = problemOf(error);
  const fields = (body?.errors ?? [])
    .map((item) => item.field)
    .filter((field): field is string => typeof field === 'string');
  // Domain problems such as ACCOUNT_IDENTIFIER_INVALID name their one field as a property.
  const single = (body as { field?: unknown } | undefined)?.field;
  if (typeof single === 'string') fields.push(single);
  return Object.fromEntries(fields.map((field) => [field, 'addFlow.fieldError']));
}

export function hasProblemCode(error: unknown, code: string): boolean {
  return error instanceof ApiError && problemReaction(error).sentCode === code;
}

/** The portfolio (or account) the request was about no longer exists. */
export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && (hasProblemCode(error, 'NOT_FOUND') || error.status === 404);
}

/**
 * The message for a failed `PUT …/identifier` shown on the field, or null when the generic
 * banner fits better (e.g. a server error with a correlation id).
 */
export function identifierErrorKey(error: unknown, kind: IdentifierKind): string | null {
  if (hasProblemCode(error, 'ACCOUNT_IDENTIFIER_INVALID')) {
    return `addAccount.identifier.invalid.${kind}`;
  }
  if (hasProblemCode(error, 'ACCOUNT_IDENTIFIER_IN_USE')) return 'addAccount.identifier.inUse';
  if (hasProblemCode(error, 'VERSION_CONFLICT')) return 'addAccount.identifier.conflict';
  return null;
}
