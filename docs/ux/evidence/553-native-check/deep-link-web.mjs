// #556 web check: /accounts/{id} opened signed out, then sign-in (± MFA / first-run step).
// Usage: TMW_APP=http://localhost:8096 node deep-link-web.mjs <outDir> [delayMs]
import { chromium } from 'playwright-core';
import { authenticator } from 'otplib';
import { mkdirSync } from 'node:fs';
const [out, delayArg] = process.argv.slice(2);
const delay = Number(delayArg ?? 0);
mkdirSync(out, { recursive: true });
const APP = process.env.TMW_APP;
const E = process.env;
const browser = await chromium.launch({ channel: 'chrome' });

const flows = [
  { name: 'mfa', email: E.TMW_EMAIL, password: E.TMW_PASSWORD, account: E.MEMBER_ACCOUNT, mfa: true, expect: 'Girokonto' },
  { name: 'no-mfa', email: E.PLAIN_EMAIL, password: E.PLAIN_PASSWORD, account: E.PLAIN_ACCOUNT, expect: 'Privatkonto' },
  // A first-run member has no accounts, so the link points at someone else's account: the route must
  // survive the setup step and the screen must say it is not available.
  { name: 'first-run-continue', email: E.FRESH_EMAIL, password: E.FRESH_PASSWORD, account: E.FOREIGN_ACCOUNT ?? E.MEMBER_ACCOUNT, setup: 'Continue', expect: 'not available to you' },
  { name: 'first-run-skip', email: E.FRESH_EMAIL, password: E.FRESH_PASSWORD, account: E.FOREIGN_ACCOUNT ?? E.MEMBER_ACCOUNT, setup: 'Skip for now', expect: 'not available to you' },
];

const only = (E.FLOWS ?? '').split(',').filter(Boolean);
const repeat = Number(E.REPEAT ?? 1);
const results = [];
for (const f of flows.filter((x) => !only.length || only.includes(x.name)).flatMap((x) => Array(repeat).fill(x))) {
  const context = await browser.newContext({ locale: 'en-GB', viewport: { width: 1280, height: 860 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('response', (r) => { if (r.url().includes('/api/') && r.status() >= 400) errors.push(`${r.status()} ${new URL(r.url()).pathname}`); });
  // TMW_API_FROM/TO: send the export's API calls to another stack (e.g. an empty workspace).
  if (E.TMW_API_TO) await page.route(E.TMW_API_FROM + '/**', (r) => r.continue({ url: r.request().url().replace(E.TMW_API_FROM, E.TMW_API_TO) }));
  if (delay) await page.route('**/api/v1/users/me', async (r) => { await new Promise((s) => setTimeout(s, delay)); await r.fallback(); });
  const path = `/accounts/${f.account}`;
  let result = 'pass', detail = '';
  try {
    await page.goto(APP + path);
    await page.getByLabel('Email').waitFor({ timeout: 30000 });
    if (await page.getByText(f.expect).count()) throw new Error('account visible before sign-in');
    await page.getByLabel('Email').fill(f.email);
    await page.getByLabel('Password').fill(f.password);
    await page.getByRole('button', { name: 'Sign in' }).last().click();
    if (f.mfa) {
      await page.getByLabel('Authenticator code').waitFor({ timeout: 30000 });
      await page.getByLabel('Authenticator code').fill(authenticator.generate(E.TMW_TOTP_SECRET));
      await page.getByRole('button', { name: 'Verify code' }).click();
    }
    if (delay) {
      // Halfway through the delayed /users/me: nothing of the linked screen may show yet.
      await page.waitForTimeout(delay / 2);
      if (await page.getByText(f.expect).count()) throw new Error('linked screen visible before the identity was known');
    }
    if (f.setup) {
      const action = page.getByRole('button', { name: f.setup, exact: true });
      await action.waitFor({ timeout: 30000 });
      if (new URL(page.url()).pathname !== path) throw new Error('route lost during first-run step: ' + page.url());
      await action.click();
    }
    await page.getByText(f.expect).first().waitFor({ timeout: 20000 });
    await page.waitForTimeout(800);
    const now = new URL(page.url()).pathname;
    if (now !== path) throw new Error('ended on ' + now);
  } catch (e) {
    result = 'FAIL';
    detail = `${e.message.split('\n')[0]} (url ${new URL(page.url()).pathname})`;
  }
  await page.screenshot({ path: `${out}/${f.name}-${results.length}.png` });
  if (result === 'FAIL') console.log('VISIBLE:', (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 400));
  results.push({ flow: f.name, result, detail, pageErrors: errors.join(' | ').slice(0, 200) });
  await context.close();
}
await browser.close();
console.table(results);
