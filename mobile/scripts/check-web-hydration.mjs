import assert from 'node:assert/strict';
import { readFileSync, statSync } from 'node:fs';
import { isAbsolute, relative, resolve, sep } from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { firefox } from 'playwright';

const exportDir = resolve(process.argv[2] ?? fileURLToPath(new URL('../dist/', import.meta.url)));
// Use the app's default API origin so both exported files and synthetic API responses can be
// served by Playwright without running a separate server.
const origin = 'http://localhost:8080';
const portfolioId = 'sample-portfolio';
const accountId = 'sample-account';
const portfolioName = 'Example Bank';
const accountName = 'Fixture Checking';
const value = {
  currency: 'EUR',
  asOf: '2026-10-31',
  valueKnown: true,
  valueStatus: 'CURRENT',
  complete: true,
  warnings: [],
};
const change = {
  amount: '0.00',
  percent: '0.0',
  contributions: '0.00',
  capitalMovements: null,
  marketAndFx: null,
};
const portfolio = {
  ...value,
  id: portfolioId,
  portfolioId,
  name: portfolioName,
  version: 1,
  portfolioType: 'BANK',
  personalAssetsDefault: false,
  portfolioTypeOverridden: false,
  activeAccountCount: 1,
  country: 'DE',
  portfolioCurrency: 'EUR',
  workspaceCurrency: 'EUR',
  total: '125.00',
  totalInWorkspaceCurrency: '125.00',
  change: { ...change, period: '1M' },
};
const account = {
  ...value,
  id: accountId,
  accountId,
  name: accountName,
  accountType: 'CASH',
  nativeCurrency: 'EUR',
  portfolioId,
  portfolioName,
  nature: 'ASSET',
  balance: '125.00',
  workspaceCurrency: 'EUR',
  balanceInWorkspaceCurrency: '125.00',
  valueInPortfolioCurrency: '125.00',
  valueSourceDate: null,
  hasTransactions: true,
  holdsPositions: false,
  status: 'ACTIVE',
  version: 1,
  reconciliation: {
    status: 'NEVER',
    reason: null,
    asOf: null,
    openDifference: null,
    currency: null,
  },
};
const history = (query) => ({
  ...value,
  period: query.get('period'),
  from: '2026-10-01',
  to: value.asOf,
  warningCodes: [],
  points: [],
  change,
  sections: [],
});
const endpoints = new Map([
  [
    'POST /api/v1/auth/login',
    () => ({
      mfaRequired: false,
      tokens: { accessToken: 'fixture-access', refreshToken: 'fixture-refresh' },
    }),
  ],
  [
    'GET /api/v1/users/me',
    (_, language) => ({
      userId: 'fixture-user',
      sessionId: 'fixture-session',
      role: 'STANDARD_USER',
      language,
    }),
  ],
  [
    'GET /api/v1/workspace',
    () => ({ id: 'fixture-workspace', name: 'Fixture Wealth', currency: 'EUR', version: 1 }),
  ],
  ['GET /api/v1/portfolios', () => [portfolio]],
  [
    'GET /api/v1/net-worth',
    () => ({
      ...value,
      totalAssets: '125.00',
      totalLiabilities: '0.00',
      netWorth: '125.00',
      sections: [],
      accounts: [{ ...account, value: account.balance }],
    }),
  ],
  ['GET /api/v1/net-worth/history', history],
  [
    'GET /api/v1/cash-flow',
    (query) => ({
      month: query.get('month'),
      income: [],
      spending: [],
      saving: [],
      pendingReview: [],
      complete: true,
    }),
  ],
  [
    `GET /api/v1/portfolios/${portfolioId}/view`,
    () => ({
      ...portfolio,
      sections: [
        {
          ...value,
          section: 'CASH_AND_SAVINGS',
          subtotal: '125.00',
          subtotalInWorkspaceCurrency: '125.00',
          accounts: [account],
        },
      ],
    }),
  ],
  [`GET /api/v1/portfolios/${portfolioId}/history`, history],
  ['GET /api/v1/accounts', () => [account]],
  [`GET /api/v1/accounts/${accountId}`, () => account],
  [
    `GET /api/v1/accounts/${accountId}/transactions`,
    (query) => ({
      content: [],
      page: { number: 0, size: Number(query.get('size')), totalElements: 0, totalPages: 0 },
    }),
  ],
  ['GET /api/v1/categories', () => []],
]);

function exportedFile(pathname) {
  const file = resolve(exportDir, `.${decodeURIComponent(pathname)}`);
  const belowExport = relative(exportDir, file);
  if (belowExport === '..' || belowExport.startsWith(`..${sep}`) || isAbsolute(belowExport)) {
    return undefined;
  }
  return [file, `${file}.html`].find((candidate) =>
    statSync(candidate, { throwIfNoEntry: false })?.isFile(),
  );
}

let browser;
before(async () => {
  const html = readFileSync(resolve(exportDir, 'overview.html'), 'utf8');
  assert.match(html, /role="progressbar"/, 'The static Overview must contain the session status');
  browser = await firefox.launch();
});
after(() => browser?.close());

for (const [locale, language, accountType] of [
  ['de-DE', 'DE', 'Girokonto'],
  ['de-CH', 'DE', 'Privatkonto'],
  ['en-GB', 'EN', 'Current account'],
]) {
  test(`static web hydration and signed-in copy in ${locale}`, async (t) => {
    const context = await browser.newContext({ locale, viewport: { width: 1280, height: 900 } });
    const problems = [];
    t.after(async () => {
      await context.close();
      assert.equal(problems.length, 0, `Browser errors in ${locale}:\n${problems.join('\n')}`);
    });

    const page = await context.newPage();
    page.setDefaultTimeout(15_000);
    page.on('pageerror', (error) => problems.push(error.message));
    page.on('console', (message) => {
      if (message.type() === 'error') problems.push(message.text());
    });
    // Intercept every request so a missing fixture cannot reach a live backend.
    await context.route('**/*', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      const key = `${request.method()} ${url.pathname}`;
      if (url.origin === origin) {
        const respond = endpoints.get(key);
        if (respond) return route.fulfill({ json: respond(url.searchParams, language) });
        if (!url.pathname.startsWith('/api/')) {
          const path = exportedFile(url.pathname);
          if (path) return route.fulfill({ path });
        }
      }
      problems.push(`Unexpected request: ${request.method()} ${url.origin}${url.pathname}`);
      return route.abort();
    });

    const german = language === 'DE';
    const documentLanguage = page.locator(`html[lang="${german ? locale : 'en'}"]`);
    await page.goto(`${origin}/overview`, { waitUntil: 'domcontentloaded' });
    await page
      .getByLabel(german ? 'E-Mail' : 'Email', { exact: true })
      .fill('fixture@example.test');
    await page
      .getByLabel(german ? 'Passwort' : 'Password', { exact: true })
      .fill('fixture-password');
    await Promise.all([
      page.waitForResponse(`${origin}/api/v1/users/me`),
      page.getByRole('button', { name: german ? 'Anmelden' : 'Sign in', exact: true }).click(),
    ]);

    await page
      .getByRole('heading', { name: german ? 'Übersicht' : 'Overview', exact: true })
      .waitFor();
    await documentLanguage.waitFor({ state: 'attached' });
    await page.getByRole('tab', { name: portfolioName, exact: true }).click();

    await page.waitForURL(`**/overview/${portfolioId}`);
    await page.getByText(accountType, { exact: true }).waitFor();
    await documentLanguage.waitFor({ state: 'attached' });
    await page.getByRole('button', { name: new RegExp(`^${accountName},`) }).click();

    await page.waitForURL(`**/accounts/${accountId}`);
    await page
      .getByRole('heading', { name: german ? 'Kontodetails' : 'Account detail', exact: true })
      .waitFor();
    await page
      .getByText(german ? 'Noch keine Transaktionen' : 'No transactions yet', { exact: true })
      .waitFor();
    await documentLanguage.waitFor({ state: 'attached' });
  });
}
