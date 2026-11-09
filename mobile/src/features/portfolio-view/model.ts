import type { components } from '@/api/schema';

/** All financial values are generated DecimalString API data; never derive a total here. */
export type PortfolioView = components['schemas']['PortfolioViewResponse'];
export type PortfolioListItem = components['schemas']['PortfolioListItemResponse'];
export type PortfolioSection = PortfolioView['sections'][number];
export type PortfolioAccount = PortfolioSection['accounts'][number];
export type Position = components['schemas']['AccountPositionResponse'];
export type PositionsResponse = components['schemas']['AccountPositionsResponse'];

export const SECTION_KEYS = {
  CASH_AND_SAVINGS: 'portfolio.section.cash',
  PILLAR_3A: 'portfolio.section.pillar3a',
  PENSION: 'portfolio.section.pension',
  DEPOT: 'portfolio.section.depot',
  CRYPTO: 'portfolio.section.crypto',
  CARDS: 'portfolio.section.cards',
  PROPERTY_AND_MORTGAGE: 'portfolio.section.property',
  OTHER: 'portfolio.section.other',
} as const;

export const PORTFOLIO_TYPES = {
  BANK: 'portfolio.type.bank',
  PORTFOLIO: 'portfolio.type.portfolio',
  PILLAR_3A: 'portfolio.type.pillar3a',
  PENSION_FUND: 'portfolio.type.pensionFund',
  CRYPTO: 'portfolio.type.crypto',
  PERSONAL_ASSETS: 'portfolio.type.personal',
  MIXED: 'portfolio.type.mixed',
} as const;

export function sectionMessageKey(section: string): string {
  return Object.prototype.hasOwnProperty.call(SECTION_KEYS, section)
    ? SECTION_KEYS[section as keyof typeof SECTION_KEYS]
    : SECTION_KEYS.OTHER;
}

export function typeMessageKey(type: string): string | null {
  return Object.prototype.hasOwnProperty.call(PORTFOLIO_TYPES, type)
    ? PORTFOLIO_TYPES[type as keyof typeof PORTFOLIO_TYPES]
    : null;
}

export function accountTypeMessageKey(type: string): string {
  return Object.prototype.hasOwnProperty.call(ACCOUNT_TYPE_KEYS, type)
    ? ACCOUNT_TYPE_KEYS[type as keyof typeof ACCOUNT_TYPE_KEYS]
    : 'portfolio.account.other';
}

export const ACCOUNT_TYPE_KEYS = {
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

export function isNegative(value: string | null | undefined): boolean {
  return (
    (typeof value === 'string' && /^-0*[1-9]/.test(value)) ||
    (typeof value === 'string' && /^-0*\.\d*[1-9]/.test(value))
  );
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

export function accountRows(section: PortfolioSection): PortfolioAccount[] {
  // The backend owns section order; only nest mortgages under their property, never reorder
  // unrelated rows or move an account into a section that the backend did not send.
  if (section.section !== 'PROPERTY_AND_MORTGAGE') return section.accounts;
  const rows = section.accounts;
  const properties = new Set(rows.map((row) => row.accountId));
  const children = new Map<string, PortfolioAccount[]>();
  for (const row of rows) {
    if (row.linkedPropertyAccountId && properties.has(row.linkedPropertyAccountId)) {
      const linked = children.get(row.linkedPropertyAccountId) ?? [];
      linked.push(row);
      children.set(row.linkedPropertyAccountId, linked);
    }
  }
  const nested = new Set([...children.values()].flat().map((row) => row.accountId));
  return rows
    .filter((row) => !nested.has(row.accountId))
    .flatMap((row) => [row, ...(children.get(row.accountId) ?? [])]);
}

export function hasPositions(account: PortfolioAccount, section: PortfolioSection): boolean {
  return (
    ['DEPOT', 'CRYPTO'].includes(section.section) &&
    account.positionsCount != null &&
    account.positionsCount > 0
  );
}
