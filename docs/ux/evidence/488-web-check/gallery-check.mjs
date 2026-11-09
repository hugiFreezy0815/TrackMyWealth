import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
// Usage: TMW_EMAIL=… TMW_PASSWORD=… node gallery-check.mjs <screenshot dir>
// A synthetic STANDARD_USER on a local stack; the web app on http://localhost:8091.
const EMAIL = process.env.TMW_EMAIL;
const PASSWORD = process.env.TMW_PASSWORD;
const URL = 'http://localhost:8091/dev/components';
const out = process.argv[2];
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const results = [];
async function open(page) {
  await page.goto('http://localhost:8091/', { waitUntil: 'networkidle' });
  await page.getByLabel('Email').fill(EMAIL);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).last().click();
  await page.getByText('Hide amounts').first().waitFor();
  // The web session lives in memory only, so stay in the app: client-side navigation.
  await page.evaluate(() => { history.pushState({}, '', '/dev/components'); dispatchEvent(new PopStateEvent('popstate')); });
  await page.getByText('Shared components (synthetic values)').waitFor();
}
async function expandScrollers(page) {
  // RN-web ScrollViews are inner overflow containers; unroll them so a full-page shot has it all.
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('*')) {
      const cs = getComputedStyle(el);
      if (/(auto|scroll)/.test(cs.overflowY) && el.scrollHeight > el.clientHeight + 1) {
        el.style.overflow = 'visible'; el.style.height = 'auto'; el.style.maxHeight = 'none'; el.style.flex = 'none';
      }
    }
    for (const el of [document.documentElement, document.body, document.getElementById('root')]) {
      if (el) { el.style.height = 'auto'; el.style.overflow = 'visible'; }
    }
  });
}
const cases = [];
for (const scheme of ['light', 'dark'])
  for (const [name, viewport, dsf] of [
    ['phone', { width: 390, height: 844 }, 3],
    ['desktop', { width: 1280, height: 900 }, 1],
    // Browser zoom 200 % on a 1280 px window = a 640 CSS px layout at 2x.
    ['zoom200', { width: 640, height: 450 }, 2],
    // 200 % text on a phone: 195 CSS px layout.
    ['phone-zoom200', { width: 195, height: 422 }, 6],
  ]) cases.push({ scheme, name, viewport, dsf });
for (const c of cases) {
  const ctx = await browser.newContext({ locale: 'en-US', viewport: c.viewport, deviceScaleFactor: c.dsf, colorScheme: c.scheme });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push('console: ' + m.text()));
  await open(page);
  const metrics = await page.evaluate(() => {
    const doc = document.scrollingElement;
    const clipped = [];
    for (const el of document.querySelectorAll('[aria-label]')) {
      const label = el.getAttribute('aria-label') || '';
      if (!/francs|euros|hidden|unknown/i.test(label)) continue;
      if (el.scrollWidth > el.clientWidth + 1) clipped.push(label);
    }
    const wide = [...document.querySelectorAll('body *')].filter((el) => {
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.right > window.innerWidth + 1;
    }).length;
    return { hScroll: doc.scrollWidth > doc.clientWidth + 1, overflowing: wide, clipped };
  });
  await expandScrollers(page);
  await page.screenshot({ path: `${out}/${c.scheme}-${c.name}.png`, fullPage: true });
  let axe = null;
  if (c.name === 'desktop' || c.name === 'phone') {
    const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
    axe = r.violations.map((v) => `${v.id}(${v.impact}) x${v.nodes.length}: ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`);
  }
  results.push({ case: `${c.scheme}-${c.name}`, ...metrics, errors, axe });
  await ctx.close();
}
// Interactions (light desktop): amount input, value-status sheet, dialogs, locale switch, date.
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light', locale: 'en-US' });
const page = await ctx.newPage();
await open(page);
const checks = {};
const amount = page.getByLabel('Opening balance');
await amount.fill("1'234.5");
checks.deCH = await page.getByText(/^DecimalString: /).innerText();
await amount.fill('1.250');
checks.ambiguous = await page.getByText(/^DecimalString: /).innerText();
await amount.blur();
checks.ambiguousError = await page.getByRole('alert').filter({ hasText: /gültigen Betrag|valid amount/ }).count();
await page.getByRole('radio', { name: 'de-DE' }).click();
await amount.fill('1.234,5');
checks.deDE = await page.getByText(/^DecimalString: /).innerText();
await page.getByRole('radio', { name: 'EN' }).click();
await page.getByRole('button', { name: /^Estimated\. / }).first().click();
checks.sheet = await page.getByText(/rests on an approximation/).isVisible();
await page.waitForTimeout(800);
await page.screenshot({ path: `${out}/light-value-status-sheet.png` });
await page.getByRole('button', { name: 'Dismiss explanation' }).click();
await page.waitForTimeout(800);
checks.sheetClosed = !(await page.getByText(/rests on an approximation/).isVisible().catch(() => false));
const row = page.getByRole('button', { name: /^Sample account/ });
checks.rowLabel = await row.getAttribute('aria-label');
checks.rowNestedButtons = await row.getByRole('button').count();
await page.getByText('Show conflict dialog').click();
checks.conflict = await page.getByText('This item was changed meanwhile').isVisible();
await page.waitForTimeout(800);
await page.screenshot({ path: `${out}/light-conflict-dialog.png` });
await page.getByRole('button', { name: 'Discard my edit' }).click();
const date = page.getByLabel('Optional date');
await date.fill('2026-11-01');
checks.optionalDate = await date.inputValue();
await date.fill('');
checks.optionalCleared = await date.inputValue();
// FR-CLI-005: "Hide amounts" (on the overview) masks every gallery amount.
await ctx.close();
{
  const c2 = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light', locale: 'en-US' });
  const p2 = await c2.newPage();
  await p2.goto('http://localhost:8091/', { waitUntil: 'networkidle' });
  await p2.getByLabel('Email').fill(EMAIL);
  await p2.getByLabel('Password').fill(PASSWORD);
  await p2.getByRole('button', { name: 'Sign in' }).last().click();
  await p2.getByText('Hide amounts').first().waitFor();
  await p2.locator('[role="switch"]').first().click();
  await p2.evaluate(() => { history.pushState({}, '', '/dev/components'); dispatchEvent(new PopStateEvent('popstate')); });
  await p2.getByText('Shared components (synthetic values)').waitFor();
  checks.hiddenAmounts = await p2.getByText('••••', { exact: true }).count();
  checks.visibleFrancs = await p2.locator('[aria-label*="francs"], [aria-label*="euros"]').count();
  checks.hiddenLabels = await p2.locator('[aria-label="hidden"]').count();
  await p2.screenshot({ path: `${out}/light-hide-amounts.png` });
  await c2.close();
}
await browser.close();
console.log(JSON.stringify({ results, checks }, null, 1));
