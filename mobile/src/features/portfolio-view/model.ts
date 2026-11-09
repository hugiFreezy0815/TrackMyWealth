import { labelFor } from '@/api/enum-label';
import type { components } from '@/api/schema';

/** All financial values are generated DecimalString API data; never derive a total here. */
export type PortfolioView = components['schemas']['PortfolioViewResponse'];
export type PortfolioListItem = components['schemas']['PortfolioListItemResponse'];
export type PortfolioSection = PortfolioView['sections'][number];
export type PortfolioAccount = PortfolioSection['accounts'][number];
export type Position = components['schemas']['AccountPositionResponse'];
export type PositionsResponse = components['schemas']['AccountPositionsResponse'];

/** Positions shown inline per depot or crypto account (spec S2, "up to 5 positions"). */
export const INLINE_POSITIONS = 5;

/** #452: read through labelFor, so a section from a newer server shows as "Other". */
export const SECTION_LABELS = {
  CASH_AND_SAVINGS: 'portfolio.section.cash',
  PILLAR_3A: 'portfolio.section.pillar3a',
  PENSION: 'portfolio.section.pension',
  DEPOT: 'portfolio.section.depot',
  CRYPTO: 'portfolio.section.crypto',
  CARDS: 'portfolio.section.cards',
  PROPERTY_AND_MORTGAGE: 'portfolio.section.property',
  OTHER: 'portfolio.section.other',
} as const;

export const PORTFOLIO_TYPE_LABELS = {
  BANK: 'portfolio.type.bank',
  PORTFOLIO: 'portfolio.type.portfolio',
  PILLAR_3A: 'portfolio.type.pillar3a',
  PENSION_FUND: 'portfolio.type.pensionFund',
  CRYPTO: 'portfolio.type.crypto',
  PERSONAL_ASSETS: 'portfolio.type.personal',
  MIXED: 'portfolio.type.mixed',
} as const;

export const ACCOUNT_TYPE_LABELS = {
  CASH: 'portfolio.account.cash',
  SAVINGS: 'portfolio.account.savings',
  SECURITIES: 'portfolio.account.securities',
  MANAGED_MANDATE: 'portfolio.account.mandate',
  PENSION: 'portfolio.account.pension',
  VESTED_BENEFITS: 'portfolio.account.vested',
  CREDIT_CARD: 'portfolio.account.card',
  MORTGAGE: 'portfolio.account.mortgage',
  LOAN: 'portfolio.account.loan',
  CRYPTO: 'portfolio.account.crypto',
  CUSTOM_ASSET: 'portfolio.account.property',
} as const;

const NO_TYPE_BADGE = '';

export function sectionMessageKey(section: string): string {
  return labelFor(SECTION_LABELS, section, SECTION_LABELS.OTHER);
}

/** Spec header #3: an unknown portfolio type shows no badge (null). */
export function typeMessageKey(type: string): string | null {
  return labelFor(PORTFOLIO_TYPE_LABELS, type, NO_TYPE_BADGE) || null;
}

export function accountTypeMessageKey(type: string): string {
  return labelFor(ACCOUNT_TYPE_LABELS, type, 'portfolio.account.other');
}

/** Calendar-day difference; never an elapsed-hours calculation or a financial calculation. */
export function daysUntil(dueDate: string, now: Date): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dueDate);
  if (!match) return null;
  const [, year, month, day] = match.map(Number);
  const due = Date.UTC(year, month - 1, day);
  if (new Date(due).getUTCMonth() !== month - 1 || new Date(due).getUTCDate() !== day) return null;
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((due - today) / 86_400_000);
}

export type AccountRowEntry = {
  account: PortfolioAccount;
  /** Shown indented under its property: only when that property is a row of the same section. */
  nested: boolean;
};

export function accountRows(section: PortfolioSection): AccountRowEntry[] {
  // The backend owns section order; only nest mortgages under their property, never reorder
  // unrelated rows or move an account into a section that the backend did not send.
  const rows = section.accounts;
  if (section.section !== 'PROPERTY_AND_MORTGAGE') {
    return rows.map((account) => ({ account, nested: false }));
  }
  const properties = new Set(rows.map((row) => row.accountId));
  const children = new Map<string, PortfolioAccount[]>();
  for (const row of rows) {
    const parent = row.linkedPropertyAccountId;
    if (parent && parent !== row.accountId && properties.has(parent)) {
      children.set(parent, [...(children.get(parent) ?? []), row]);
    }
  }
  const nested = new Set([...children.values()].flat().map((row) => row.accountId));
  return rows
    .filter((row) => !nested.has(row.accountId))
    .flatMap((row) => [
      { account: row, nested: false },
      ...(children.get(row.accountId) ?? []).map((child) => ({ account: child, nested: true })),
    ]);
}

/** Positions exist only for a depot or crypto account the server counted positions for. */
export function hasPositions(account: PortfolioAccount, section: PortfolioSection): boolean {
  return (
    (section.section === 'DEPOT' || section.section === 'CRYPTO') &&
    account.positionsCount != null &&
    account.positionsCount > 0
  );
}

/** Accounts whose value is unknown: named in the explanation sheet (NFR-UX-003). */
export function unknownAccounts(sections: readonly PortfolioSection[]): PortfolioAccount[] {
  return sections
    .flatMap((section) => section.accounts)
    .filter((account) => !account.valueKnown || account.valueStatus === 'UNKNOWN');
}
