// #496 conversion web check (PR #597) (S4 converted amount, rate, monospace bank code). Synthetic demo data only.
// Usage: node conversion-web.mjs <web url> <api url> <creds.json> <out dir>
import { chromium } from 'playwright-core';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';

const [web, api, credsPath, out] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const { partner } = JSON.parse(readFileSync(credsPath, 'utf8')).users;
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` - ${detail}` : ''}`);
};

async function apiGet(path, token) {
  const r = await fetch(`${api}/api/v1${path}`, { headers: { Authorization: `Bearer ${token}` } });
  return r.json();
}
const login = await (
  await fetch(`${api}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: partner.email, password: partner.password }),
  })
).json();
const token = login.tokens.accessToken;
const accounts = await apiGet('/accounts', token);
const byName = Object.fromEntries(accounts.map((a) => [a.name, a]));
async function find(accountName, externalId) {
  const account = byName[accountName];
  const page = await apiGet(`/accounts/${account.id}/transactions?size=100`, token);
  const rows = page.content ?? page.items ?? page;
  return { accountId: account.id, id: rows.find((t) => t.externalId === externalId || t.externalId?.endsWith(':' + externalId))?.id };
}
const fx = await find('Demo · Cloudberry Current', 'tmw-597-check-fx');
const camt = await find('Demo · Paper Moon Current', 'REF-597-1');
if (!fx.id || !camt.id) throw new Error('seeded rows not found');

const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function session({ viewport, colorScheme = 'light' }) {
  const context = await browser.newContext({ viewport, colorScheme, locale: 'en-GB' });
  const page = await context.newPage();
  const errors = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.goto(web);
  await page.getByLabel('Email').fill(partner.email);
  await page.getByLabel('Password', { exact: true }).fill(partner.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByText('Net worth').first().waitFor({ timeout: 30000 });
  return { context, page, errors };
}
async function open(page, target) {
  await page.evaluate((path) => {
    history.pushState({}, '', path);
    dispatchEvent(new PopStateEvent('popstate'));
  }, `/accounts/${target.accountId}/transactions/${target.id}`);
  await page.getByRole('button', { name: /Details/ }).waitFor({ timeout: 30000 });
}
async function expandDetails(page) {
  await page.getByRole('button', { name: /Details/ }).click();
  await page.getByText('Converted amount').or(page.getByText('Bank code')).first().waitFor();
}
async function shot(page, file) {
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${out}/${file}`, fullPage: false });
}

// 1. Phone, light, amounts visible.
{
  const { context, page, errors } = await session({ viewport: { width: 390, height: 844 } });
  await open(page, fx);
  check('Converted amount hidden before Details', (await page.getByText('Converted amount').count()) === 0);
  await expandDetails(page);
  const row = page.getByText(/^≈.*CHF\s80\.24\sat\s0\.951234$/);
  const text = await row.textContent();
  check('Converted amount and rate shown together', (await row.count()) === 1, text);
  const label = await row.getAttribute('aria-label');
  check('Conversion aria-label is spoken text', /approximately minus 80\.24 Swiss francs at 0\.951234/.test(label ?? ''), label);
  check('No separate FX rate row', (await page.getByText('FX rate').count()) === 0);
  await page.getByText('Converted amount').scrollIntoViewIfNeeded();
  await shot(page, '01-conversion-phone.png');
  const hScroll = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
  check('No horizontal scroll at 390 px', !hScroll);
  const axe = await new AxeBuilder({ page }).analyze();
  const serious = axe.violations.filter((v) => ['serious', 'critical'].includes(v.impact));
  writeFileSync(`${out}/axe-conversion-phone.json`, JSON.stringify(axe.violations, null, 2));
  check('axe: no serious/critical violations (conversion view)', serious.length === 0,
    axe.violations.map((v) => `${v.id}(${v.impact}, ${v.nodes.length})`).join(', '));

  await context.close();
  check('No console errors (phone run)', errors.length === 0, errors.join(' | ').slice(0, 300));
}

// 2. Phone, amounts hidden.
{
  const { context, page } = await session({ viewport: { width: 390, height: 844 } });
  const toggle = page.getByRole('switch', { name: 'Hide amounts' });
  if ((await toggle.count()) === 0) {
    await page.evaluate(() => {
      history.pushState({}, '', '/settings');
      dispatchEvent(new PopStateEvent('popstate'));
    });
  }
  await page.getByRole('switch', { name: 'Hide amounts' }).first().click();
  await open(page, fx);
  await expandDetails(page);
  const body = await page.locator('body').innerText();
  check('Hidden: label still shown', body.includes('Converted amount'));
  check('Hidden: amount and rate absent from text', !/80[.,]2|0[.,]951234/.test(body));
  const labels = await page.$$eval('[aria-label]', (els) => els.map((e) => e.getAttribute('aria-label')).join('\n'));
  check('Hidden: amount and rate absent from aria-labels', !/80[.,]2|0[.,]951234/.test(labels));
  await page.getByText('Converted amount').scrollIntoViewIfNeeded();
  await shot(page, '02-conversion-hidden-phone.png');
  await context.close();
}

// 3. Bank code in monospace; desktop, dark.
{
  const { context, page } = await session({ viewport: { width: 1280, height: 900 }, colorScheme: 'dark' });
  await open(page, camt);
  await expandDetails(page);
  const code = page.getByText('PMNT-CCRD-POSD', { exact: true });
  const font = await code.evaluate((el) => getComputedStyle(el).fontFamily);
  const labelFont = await page.getByText('Bank code', { exact: true }).evaluate((el) => getComputedStyle(el).fontFamily);
  check('Bank code font is monospace', /mono|Menlo|Consolas|Courier/i.test(font) && font !== labelFont, font);
  check('Same-currency booking shows no conversion', (await page.getByText('Converted amount').count()) === 0);
  await code.scrollIntoViewIfNeeded();
  await shot(page, '03-bank-code-desktop-dark.png');
  const axe = await new AxeBuilder({ page }).analyze();
  const serious = axe.violations.filter((v) => ['serious', 'critical'].includes(v.impact));
  writeFileSync(`${out}/axe-bank-code-desktop-dark.json`, JSON.stringify(axe.violations, null, 2));
  check('axe: no serious/critical violations (bank code, dark)', serious.length === 0,
    axe.violations.map((v) => `${v.id}(${v.impact}, ${v.nodes.length})`).join(', '));
  await context.close();
}

await browser.close();
writeFileSync(`${out}/results.json`, JSON.stringify(results, null, 2));
process.exit(results.every((r) => r.ok) ? 0 : 1);
