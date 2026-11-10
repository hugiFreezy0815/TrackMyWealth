// Synthetic fixtures for the add-flow tests (never real institution or member data).
import { ApiError } from '@/api/client';

import type { Account, Institution, Portfolio } from './model';

export const portfolio = {
  id: 'p-1',
  name: 'DKB',
  country: 'DE',
  institutionType: 'BANK',
  portfolioCurrency: 'EUR',
  catalogueInstitutionId: 'dkb',
  logoUrl: null,
  identifier: null,
  portfolioType: 'BANK',
  portfolioTypeOverridden: false,
  personalAssetsDefault: false,
  status: 'ACTIVE',
  version: 2,
} satisfies Portfolio;

export function createdAccount(flags: Partial<Account> = {}): Account {
  return {
    id: 'a-1',
    portfolioId: 'p-1',
    name: 'Account',
    accountType: 'CASH',
    nativeCurrency: 'EUR',
    nature: 'ASSET',
    holdsPositions: false,
    hasTransactions: true,
    hasStatementCycle: false,
    hasAmortisation: false,
    hasContributionLimit: false,
    discretionary: false,
    manualValuation: false,
    countsAsSaving: false,
    savingsPocketAccountId: null,
    status: 'ACTIVE',
    archivedAt: null,
    version: 3,
    warnings: [],
    reconciliation: {} as Account['reconciliation'],
    ...flags,
  };
}

export const dkb: Institution = {
  id: 'dkb-id',
  name: 'DKB',
  country: 'DE',
  institutionType: 'BANK',
  identifier: null,
  logoUrl: null,
  importFormats: ['CSV', 'PDF'],
};

/** An error response with a problem body, as the API client throws it. */
export function problem(status: number, code: string, extra: Record<string, unknown> = {}) {
  return new ApiError(code, status, { code, status, title: code, ...extra });
}

export const theme = {
  textPrimary: '#0B1C3F',
  textSecondary: '#53627A',
  textTertiary: '#5E6D83',
  bgSurface: '#FFFFFF',
  bgSubtle: '#ECF1F4',
  bgInverse: '#0B1C3F',
  bgOverlay: '#0B1C3F99',
  brandSubtle: '#E0F5F1',
  borderDefault: '#E1E7EC',
  borderInteractive: '#64758A',
  statusLoss: '#C42F37',
  statusLossSubtle: '#FDECEC',
  statusWarning: '#8F5200',
  brandPrimary: '#00857A',
  brandLink: '#006D64',
  textOnBrand: '#FFFFFF',
};
