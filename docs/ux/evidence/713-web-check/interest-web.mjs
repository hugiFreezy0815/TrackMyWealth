// #713 web check: field 7 "Interest paid in {year}" on the loan detail at phone and desktop width,
// EN + DE, light + dark, every state. Synthetic data; the API is mocked with page.route.
//   APP=http://localhost:<port> OUT=<dir> node interest-web.mjs
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { chromium } from 'playwright-core';

const APP = process.env.APP;
const OUT = process.env.OUT;
const API = 'http://localhost:8099';
const YEAR = new Date().getFullYear();
mkdirSync(OUT, { recursive: true });
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
};
const cors = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': '*',
  'access-control-expose-headers': '*',
};
const page0 = (content, size = 50) => ({
  content,
  page: { number: 0, size, totalElements: content.length, totalPages: content.length ? 1 : 0 },
});
const loan = {
  id: 'loan', portfolioId: 'p-1', name: 'Home loan', accountType: 'LOAN', nativeCurrency: 'EUR',
  nature: 'LIABILITY', holdsPositions: false, hasTransactions: true, hasStatementCycle: false,
  hasAmortisation: true, hasContributionLimit: false, discretionary: false, manualValuation: false,
  countsAsSaving: false, savingsPocketAccountId: null, status: 'ACTIVE', archivedAt: null,
  openedAt: null, closedAt: null, jurisdiction: null, version: 3, warnings: [], reconciliation: {},
};
const entry = (id, dueDate, principal, interest, remaining) => ({
  id, accountId: 'loan', dueDate, amount: '650.00', principalComponent: principal,
  interestComponent: interest, remainingBalance: remaining, currency: 'EUR', version: 1,
  createdAt: '2026-01-01T00:00:00Z',
});
const repayment = (id, bookingDate, principal, interest, estimated) => ({
  id, loanAccountId: 'loan', payingAccountId: 'payer', amortisationScheduleEntryId: null,
  bookingDate, amount: '650.00', principalAmount: principal, interestAmount: interest,
  currency: 'EUR', splitEstimated: estimated, splitSource: estimated ? 'SCHEDULE' : 'LENDER',
  externalId: null, createdAt: '2026-10-30T00:00:00Z', transactions: [],
});
const year = (other) => ({
  year: YEAR, amountPaid: '6500.00', principalPaid: '5147.10', interestPaid: '1352.90',
  unknownSplitAmount: '0.00', paymentCount: 10, splitEstimated: false, ...other,
});
const STATES = {
  amount: { years: [year()] },
  estimated: { years: [year({ splitEstimated: true })] },
  unknown: { years: [year({ interestPaid: null, principalPaid: null, unknownSplitAmount: '6500.00' })] },
  error: { status: 403 },
  hidden: { years: [year({ year: YEAR - 1 })] },
  partial: { years: [year({ interestPaid: '1222.90', principalPaid: '4627.10', unknownSplitAmount: '650.00' })] },
};
const TEXT = {
  EN: { title: `Interest paid in ${YEAR}`, header: 'Repayments', seeAll: 'See all' },
  DE: { title: `Bezahlte Zinsen ${YEAR}`, header: 'Rückzahlungen', seeAll: 'Alle anzeigen' },
};

async function open(browser, { lang, width, scheme, state }) {
  const context = await browser.newContext({
    locale: lang === 'DE' ? 'de-CH' : 'en-GB',
    viewport: { width, height: 1000 },
    colorScheme: scheme,
  });
  const page = await context.newPage();
  const unknown = new Set();
  await page.route(`${API}/**`, async (route) => {
    const req = route.request();
    if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
    const path = new URL(req.url()).pathname;
    const json = (body, status = 200) =>
      route.fulfill({ status, headers: { ...cors, 'content-type': 'application/json' }, body: JSON.stringify(body) });
    if (path === '/api/v1/auth/login')
      return json({ mfaRequired: false, mfaChallengeToken: null,
        tokens: { accessToken: 'a', refreshToken: 'r', expiresInSeconds: 3600, tokenType: 'Bearer' } });
    if (path === '/api/v1/users/me') return json({ language: lang, role: 'MEMBER', sessionId: 's', userId: 'u' });
    if (path === '/api/v1/workspace')
      return json({ id: '00000000-0000-4000-8000-000000000001', name: 'My finances', currency: 'EUR', version: 1 });
    if (path === '/api/v1/portfolios')
      return json(page0([{ id: 'p-1', catalogueInstitutionId: null, name: 'Example Bank', country: 'DE',
        institutionType: 'BANK', identifier: null, logoUrl: null, portfolioCurrency: 'EUR',
        portfolioType: 'BANK', portfolioTypeOverridden: false, personalAssetsDefault: false,
        status: 'ACTIVE', version: 1 }]));
    if (path === '/api/v1/accounts') return json(page0([loan]));
    if (path === '/api/v1/net-worth' || path === '/api/v1/cash-flow')
      return json({ type: 'about:blank', title: 'Unavailable', status: 503 }, 503);
    if (path === '/api/v1/portfolios/p-1/view') return json({ sections: [] });
    if (path === '/api/v1/accounts/loan') return json(loan);
    if (path === '/api/v1/accounts/loan/amortisation-schedule')
      return json(page0([entry('e1', '2026-11-30', '520.00', '130.00', '51880.00'),
        entry('e2', '2034-09-30', '648.40', '1.60', '0.00')], 200));
    if (path === '/api/v1/accounts/loan/repayments')
      return json({ content: [repayment('r1', '2026-10-30', '518.70', '131.30', false),
        repayment('r2', '2026-09-30', '517.40', '132.60', true), repayment('r3', '2026-08-30', '516.10', '133.90', false)],
        page: { number: 0, size: 3, totalElements: 10, totalPages: 4 } });
    if (path === '/api/v1/accounts/loan/repayments/yearly-totals') {
      const s = STATES[state];
      if (s.status) return json({ type: 'about:blank', title: 'Forbidden', status: 403, code: 'FORBIDDEN' }, 403);
      return json({ loanAccountId: 'loan', currency: 'EUR', years: s.years });
    }
    unknown.add(path);
    return json(page0([]));
  });
  await page.goto(APP);
  await page.getByLabel(lang === 'DE' ? 'Server' : 'Server').first().waitFor({ timeout: 60000 });
  const inputs = page.locator('input');
  await inputs.nth(0).fill(API);
  await inputs.nth(1).fill('member@example.com');
  await page.locator('input[type=password]').first().fill('synthetic-password');
  await page.locator('input[type=password]').first().press('Enter');
  await page.waitForTimeout(2500);
  await page.evaluate(() => {
    window.history.pushState({}, '', '/accounts/loan');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  return { context, page, unknown };
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
  for (const lang of (process.env.LANGS ?? 'EN,DE').split(',')) {
    for (const scheme of (process.env.SCHEMES ?? 'light,dark').split(',')) {
      for (const width of [320, 1280]) {
        const states = scheme === 'light' ? Object.keys(STATES) : ['estimated'];
        for (const state of states) {
          const tag = `${lang} ${scheme} ${width}px ${state}`;
          const { context, page, unknown } = await open(browser, { lang, width, scheme, state });
          const t = TEXT[lang];
          const header = page.getByRole('heading', { name: t.header });
          try {
            await header.waitFor({ timeout: 30000 });
          } catch {
            await page.screenshot({ path: join(OUT, `FAIL-${tag.replaceAll(' ', '-')}.png`) });
            check(`${tag}: Repayments panel shows`, false, [...unknown].join(' '));
            await context.close();
            continue;
          }
          await page.waitForTimeout(1200);
          const title = page.getByText(t.title, { exact: true });
          const shown = (await title.count()) > 0;
          check(`${tag}: field 7 ${state === 'hidden' ? 'absent' : 'present'}`, state === 'hidden' ? !shown : shown);
          const m = await page.evaluate(({ titleText, headerText }) => {
            const all = [...document.querySelectorAll('div,span')];
            const h = all.find((el) => el.textContent === headerText && el.getAttribute('role') === 'heading');
            const ti = all.find((el) => el.textContent === titleText && el.children.length === 0);
            const panel = h?.parentElement?.parentElement;
            const rows = panel ? [...panel.children] : [];
            return {
              pageOverflow: document.documentElement.scrollWidth - window.innerWidth,
              // Field 7 is the first block after the title row.
              firstBlockIsField: ti ? rows[1]?.contains(ti) ?? false : null,
              panelOverflow: panel ? panel.scrollWidth - panel.clientWidth : null,
            };
          }, { titleText: t.title, headerText: t.header });
          check(`${tag}: no horizontal overflow`, m.pageOverflow <= 0 && (m.panelOverflow ?? 0) <= 0, JSON.stringify(m));
          if (shown) {
            // Field 7 sits between the title row and the first repayment row.
            const hb = await header.boundingBox();
            const tb = await title.first().boundingBox();
            const firstRow = page.getByText(lang === 'DE' ? '30.10.2026' : '30 Oct 2026', { exact: true }).first();
            const rb = await firstRow.boundingBox();
            check(`${tag}: field 7 is the first block of the panel`, tb.y > hb.y + hb.height - 1 && tb.y + tb.height < rb.y,
              JSON.stringify({ header: hb.y, field: tb.y, firstRow: rb.y }));
          }
          const seeAll = page.getByRole('button', { name: t.seeAll });
          if (await seeAll.count()) {
            const [hb, sb] = [await header.boundingBox(), await seeAll.first().boundingBox()];
            check(`${tag}: See all in the title row`, Math.abs(hb.y + hb.height / 2 - (sb.y + sb.height / 2)) < 16);
          }
          await header.scrollIntoViewIfNeeded();
          const panel = header.locator('xpath=../..');
          await panel.screenshot({ path: join(OUT, `${lang}-${scheme}-${width}-${state}.png`) });
          if (width === 320 && (state === 'partial' || state === 'error' || scheme === 'dark')) {
            const { violations } = await new AxeBuilder({ page })
              .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
              .analyze();
            const ids = violations.map((v) => `${v.id}(${v.nodes.length})`).join(', ');
            check(`${tag}: axe`, violations.length === 0, ids);
          }
          await context.close();
        }
      }
    }
  }
} finally {
  await browser.close();
  writeFileSync(join(OUT, 'results.json'), JSON.stringify(results, null, 2));
}
process.exit(results.every((r) => r.ok) ? 0 : 1);
