// PR #562 (#496) web check: value history on Overview and Portfolio View against a seeded stack.
// Usage (playwright-core + @axe-core/playwright installed next to this file, system Chrome):
//   APP=http://localhost:8098 OUT=<dir> STAGE=uncertain|settled TMW_EMAIL=.. TMW_PASSWORD=.. \
//     node history-web.mjs
// STAGE=uncertain right after seeding (no historical FX yet: VALUE_UNKNOWN), STAGE=settled once
// the stack has fetched FX rates.
import AxeBuilder from '@axe-core/playwright';
import { chromium } from 'playwright-core';

const { APP, OUT, STAGE = 'settled' } = process.env;
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok: Boolean(ok) });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`);
};

const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function session({ viewport = { width: 390, height: 844 }, colorScheme = 'light', storageState } = {}) {
  const context = await browser.newContext({ locale: 'en-GB', viewport, colorScheme, storageState });
  const page = await context.newPage();
  const requests = [];
  const errors = [];
  page.on('request', (r) => r.url().includes('/history?') && requests.push(new URL(r.url())));
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(APP + '/', { waitUntil: 'networkidle' });
  await page.getByLabel('Email').fill(process.env.TMW_EMAIL);
  await page.getByLabel('Password').fill(process.env.TMW_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).last().click();
  await page.getByRole('img', { name: /^Value history/ }).first().waitFor({ timeout: 20000 });
  await page.waitForLoadState('networkidle');
  return { context, page, requests, errors };
}

// RN-web scrolls an inner div: unroll it for a full-page shot, then restore the layout.
async function shot(page, name) {
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('div')) {
      const s = getComputedStyle(el);
      if (s.overflowY === 'auto' || s.overflowY === 'scroll') {
        el.dataset.unrolled = el.getAttribute('style') ?? '';
        Object.assign(el.style, { overflow: 'visible', height: 'auto', maxHeight: 'none' });
      }
    }
    // The fixed tab bar would float over the unrolled page.
    for (const nav of document.querySelectorAll('nav')) {
      nav.dataset.unrolled = nav.getAttribute('style') ?? '';
      nav.style.visibility = 'hidden';
    }
  });
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true });
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('[data-unrolled]')) {
      el.setAttribute('style', el.dataset.unrolled);
      delete el.dataset.unrolled;
    }
  });
}
async function axe(page, label) {
  const r = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  const v = r.violations.map((x) => `${x.id}(${x.impact}) x${x.nodes.length}`);
  check(`axe ${label}: no violations`, v.length === 0, v.join('; '));
}
const text = (page) => page.evaluate(() => document.body.innerText);
const checked = (page, name) => page.getByRole('radio', { name }).first().getAttribute('aria-checked');
const y = async (locator) => (await locator.first().boundingBox())?.y ?? NaN;

// ---------- Overview ----------
let s = await session();
let { page } = s;
const card = page.getByText('Net worth', { exact: true }).first();
const r0 = s.requests.find((u) => u.pathname === '/api/v1/net-worth/history');
check('Overview loads history with currency and default 1M', r0?.searchParams.get('period') === '1M', r0?.search);
check('1M selected by default', (await checked(page, '1M, Past month')) === 'true');
let body = await text(page);

// M03 order inside the card: chip + "past month", split, chart, then the period chips.
const chipY = await y(page.getByText('past month'));
const chartY = await y(page.getByRole('img', { name: /^Value history/ }));
const chipsY = await y(page.getByRole('radio', { name: '1M, Past month' }));
check('M03 order: change chip above the chart above the period chips', chipY < chartY && chartY < chipsY, `chip ${chipY} chart ${chartY} chips ${chipsY}`);
check('Card title above everything', (await y(card)) < chipY);

if (STAGE === 'uncertain') {
  check('Value unknown: no percentage on the chip (server sent 15.66)', !body.includes('+15.7%'));
  check('Value unknown: explanation shown', body.includes('This change may be off'));
  check('Value unknown: catalogue warning shown', body.includes('A historical value is unavailable for at least one account.'));
} else {
  check('Settled: chip shows the server percentage', /[+−]\d+\.\d%/.test(body.split('past month')[0].slice(-40)), body.split('past month')[0].slice(-40));
  check('Settled: no uncertainty note', !body.includes('This change may be off'));
}
check('Account names / server sentences never shown', !/Privatkonto|value unknown|Currency conversion unavailable/.test(body));
check('Section tile names the period of its change', (body.match(/past month/g) ?? []).length >= 2);
check('Portfolio rows show the 1M %', body.includes('+3.1%') && body.includes('+26.7%'));
await shot(page, `${STAGE}-01-overview-1M-phone`);
await axe(page, `Overview (${STAGE})`);

if (STAGE === 'settled') {
  // Switch period, then Portfolio View shares it.
  await page.getByRole('radio', { name: '1W, Past week' }).first().click();
  await page.getByText('past week').first().waitFor();
  body = await text(page);
  check('1W: request and range label', s.requests.at(-1)?.searchParams.get('period') === '1W' && body.includes('past week'));
  check('1W: portfolio row % hidden (list API is 1M only)', !body.includes('+3.1%'));
  check('1W saved on the device', (await page.evaluate(() => localStorage.getItem('trackmywealth.history-period'))) === '1W');
  for (const [name, range] of [
    ['YTD, Year to date', 'year to date'],
    ['1Y, Past year', 'past year'],
    ['Max, All time', 'all time'],
  ]) {
    await page.getByRole('radio', { name }).first().click();
    await page.getByText(range).first().waitFor();
    check(`${name.split(',')[0]} renders a chart`, await page.getByRole('img', { name: /^Value history from/ }).first().isVisible());
  }
  await page.getByRole('radio', { name: '1W, Past week' }).first().click();
  await page.getByText('past week').first().waitFor();
  const storage = await s.context.storageState();

  await page.getByRole('button', { name: 'Synthetic Bank' }).last().click();
  await page.getByText('Portfolio currency EUR').waitFor({ timeout: 15000 });
  await page.waitForLoadState('networkidle');
  const rp = s.requests.filter((u) => /\/portfolios\/.+\/history/.test(u.pathname)).at(-1);
  check('Portfolio View uses the shared period', rp?.searchParams.get('period') === '1W' && (await checked(page, '1W, Past week')) === 'true', rp?.search);
  await page.screenshot({ path: `${OUT}/settled-02-portfolio-1W-phone.png` });
  await axe(page, 'Portfolio View');

  await page.getByRole('navigation').getByText('Overview').click();
  await page.waitForURL(/\/overview$/);
  await page.getByRole('switch', { name: 'Hide amounts' }).first().click();
  await page.getByRole('img', { name: 'Value history. Amounts and dates are hidden.' }).first().waitFor();
  body = await text(page);
  check('Hidden: no percentages or chart dates', !/\d+\.\d%/.test(body) && !/\d{1,2} (Oct|Nov) 2026\n/.test(body.replace(/As of [^\n]+/, '')));
  await shot(page, 'settled-03-overview-hidden-phone');
  await page.getByRole('switch', { name: 'Hide amounts' }).first().click();

  await s.context.setOffline(true);
  await page.evaluate(() => window.dispatchEvent(new Event('offline')));
  await page.waitForTimeout(500);
  check('Offline: cached chart still shown', await page.getByRole('img', { name: /^Value history from/ }).first().isVisible());
  await s.context.setOffline(false);
  check('No page errors', s.errors.length === 0, s.errors.join(' | '));
  await s.context.close();

  s = await session({ storageState: storage });
  check('Saved period restored at the next sign-in', (await checked(s.page, '1W, Past week')) === 'true');
  await s.context.close();

  s = await session({ viewport: { width: 320, height: 800 } });
  const overflow = await s.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check('320 px: no horizontal scroll', overflow <= 0, `${overflow}px`);
  await shot(s.page, 'settled-04-overview-320');
  await s.context.close();

  s = await session({ viewport: { width: 1280, height: 900 }, colorScheme: 'dark' });
  await shot(s.page, 'settled-05-overview-desktop-dark');
  await axe(s.page, 'Overview desktop dark');
}
await s.context.close();
await browser.close();
const failed = results.filter((r) => !r.ok).length;
console.log(`\n${results.length - failed}/${results.length} checks passed (${STAGE})`);
process.exit(failed ? 1 : 0);
