import type { components } from '@/api/schema';

export type Portfolio = components['schemas']['PortfolioListItemResponse'];
export type NetWorth = components['schemas']['NetWorthResponse'];
export type Account = components['schemas']['AccountListItemResponse'];
export type CashFlow = components['schemas']['CashFlowResponse'];
export type CurrencyAmount = components['schemas']['CurrencyAmount'];
export type Section = components['schemas']['NetWorthSectionResponse'];

/** Empty means no figures have ever been entered, not merely a total of zero. */
export function isFirstRunEmpty(
  portfolios: readonly Portfolio[],
  netWorth: NetWorth,
): boolean {
  const onlyDefault =
    portfolios.length === 0 ||
    (portfolios.length === 1 &&
      portfolios[0].personalAssetsDefault &&
      portfolios[0].activeAccountCount === 0);
  return onlyDefault && netWorth.accounts.length === 0;
}

export function unreconciledAccounts(accounts: readonly Account[]): Account[] {
  return accounts.filter((account) => !account.archived && account.valueStatus === 'UNRECONCILED');
}

export function unknownValuations(netWorth: NetWorth): NetWorth['accounts'] {
  return netWorth.accounts.filter((account) => !account.valueKnown);
}

/** Decimal-string magnitude comparison without Number, parseFloat or arithmetic. */
export function compareMagnitude(left: string, right: string): number {
  const normalize = (value: string) => {
    const [integer, fraction = ''] = value.replace(/^-/, '').split('.');
    return { integer: integer.replace(/^0+(?=\d)/, ''), fraction: fraction.replace(/0+$/, '') };
  };
  const a = normalize(left);
  const b = normalize(right);
  if (a.integer.length !== b.integer.length) return a.integer.length - b.integer.length;
  if (a.integer !== b.integer) return a.integer < b.integer ? -1 : 1;
  const paddedA = a.fraction.padEnd(Math.max(a.fraction.length, b.fraction.length), '0');
  const paddedB = b.fraction.padEnd(Math.max(a.fraction.length, b.fraction.length), '0');
  return paddedA === paddedB ? 0 : paddedA < paddedB ? -1 : 1;
}

/** Only the *order* changes here; all amounts remain exactly as supplied by the API. */
export function largestLiabilities(netWorth: NetWorth) {
  return netWorth.accounts
    .filter((account) => account.nature === 'LIABILITY' && account.valueKnown && account.value !== null)
    .sort((a, b) => compareMagnitude(b.value!, a.value!));
}

export function currencyAmount(
  entries: readonly CurrencyAmount[],
  currency: string,
): CurrencyAmount | undefined {
  return entries.find((entry) => entry.currency === currency);
}

/** The UI hides pending review when it is genuinely zero; it never derives a new amount. */
export function hasNonZeroAmount(value: string | null | undefined): boolean {
  return value != null && !/^-?0+(?:\.0+)?$/.test(value);
}

export function scopeCurrencies(workspaceCurrency: string | undefined): string[] {
  return Array.from(new Set([workspaceCurrency, 'CHF', 'EUR', 'USD', 'GBP'].filter(
    (value): value is string => Boolean(value),
  )));
}

export function monthKey(now: Date): string {
  return [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0')].join('-');
}
