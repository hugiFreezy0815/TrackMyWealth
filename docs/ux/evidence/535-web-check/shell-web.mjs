// Web check for #535 (US-01-13 app shell): screenshots at 390 / 800 / 1280 px and the web
// sign-out rules, on the production web export against a stack seeded with synthetic data
// (docs/ux/evidence/494-web-check/seed.mjs).
// Usage: TMW_EMAIL=... TMW_PASSWORD=... TMW_APP=http://localhost:8086 \
//   TMW_ELSEWHERE=http://localhost:8095/actuator/health [TMW_BROWSER=webkit] \
//   node shell-web.mjs <accountId> <outDir>
// Needs playwright-core (scratch folder) and the installed Google Chrome, or Playwright's WebKit
// build with TMW_BROWSER=webkit. Playwright switches Chrome's back-forward cache off by default;
// this script switches it on again, as in a real browser.
import { chromium, webkit } from 'playwright-core';
import { mkdirSync, writeFileSync } from 'node:fs';

const [accountId, out] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const APP = process.env.TMW_APP ?? 'http://localhost:8086';
const ELSEWHERE = process.env.TMW_ELSEWHERE ?? 'http://localhost:8095/actuator/health';
const engine = process.env.TMW_BROWSER ?? 'chrome';
const browser =
  engine === 'webkit'
    ? await webkit.launch()
    : await chromium.launch({ channel: 'chrome', ignoreDefaultArgs: ['--disable-back-forward-cache'] });
const results = { browser: `${engine} ${browser.version()}`, screenshots: {}, signOut: {} };

// Anything of the member's session: names from the seed, the greeting, any amount.
const SESSION_DATA = /Girokonto|Tagesgeld|DKB|Synthetic household|member@example\.test|Good (morning|afternoon|evening)|\d[\d',.]*\.\d{2}/;

async function open(width, height) {
  const context = await browser.newContext({ locale: 'en-GB', viewport: { width, height } });
  await context.addInitScript(() => {
    addEventListener('pageshow', (event) => console.log(`[pageshow] persisted=${event.persisted} ${location.pathname}`));
  });
  const page = await context.newPage();
  const log = [];
  page.on('pageerror', (e) => log.push('pageerror: ' + e.message));
  page.on('console', (m) => {
    if (m.type() === 'error' || m.text().startsWith('[pageshow]')) log.push(m.text().slice(0, 200));
  });
  return { context, page, log };
}

const signInForm = (page) => page.getByRole('button', { name: 'Sign in' }).last();

async function signIn(page) {
  await page.getByLabel('Email').fill(process.env.TMW_EMAIL);
  await page.getByLabel('Password').fill(process.env.TMW_PASSWORD);
  await signInForm(page).click();
  await page.getByText(/Good (morning|afternoon|evening)/).first().waitFor({ timeout: 30000 });
}

/** Client-side navigation: the web session lives in memory, a document load would end it. */
async function go(page, path, ready) {
  await page.evaluate((to) => {
    history.pushState({}, '', to);
    dispatchEvent(new PopStateEvent('popstate'));
  }, path);
  await page.getByText(ready).first().waitFor({ timeout: 30000 });
}

const hScroll = (page) =>
  page.evaluate(() => document.scrollingElement.scrollWidth > document.scrollingElement.clientWidth + 1);

/** What a person sees now: the path, the sign-in form or session data. */
async function seen(page) {
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await page.waitForTimeout(1500);
  const text = await page.evaluate(() => document.body.innerText);
  const leak = text.match(SESSION_DATA);
  return {
    path: new URL(page.url()).pathname.replace(accountId, '{accountId}'),
    signInForm: await signInForm(page).isVisible().catch(() => false),
    sessionData: leak ? leak[0] : null,
  };
}

// --- 1. Screenshots: sign-in, Overview and Settings at 390, 800 and 1280 px -----------------
for (const [name, width, height] of [['phone-390', 390, 844], ['tablet-800', 800, 1100], ['desktop-1280', 1280, 900]]) {
  const { context, page, log } = await open(width, height);
  await page.goto(APP + '/', { waitUntil: 'networkidle' });
  await signInForm(page).waitFor();
  const shot = async (screen) => {
    await page.waitForTimeout(700);
    await page.screenshot({ path: `${out}/${screen}-${name}.png` });
    return { hScroll: await hScroll(page) };
  };
  const r = { 'sign-in': await shot('sign-in') };
  await signIn(page);
  r.overview = await shot('overview');
  await go(page, '/settings', 'Sign out');
  r.settings = await shot('settings');
  r.log = log.filter((l) => !l.startsWith('[pageshow]'));
  results.screenshots[name] = r;
  await context.close();
}

// --- 2. Sign out from Settings after visiting an account screen ----------------------------
{
  const { context, page, log } = await open(1280, 900);
  await page.goto(APP + '/', { waitUntil: 'networkidle' });
  await signIn(page);
  await go(page, `/accounts/${accountId}`, /^Showing 50 of \d+$/);
  await go(page, '/settings', 'Sign out');
  await page.evaluate(() => { window.__beforeSignOut = true; });
  const historyLength = await page.evaluate(() => history.length);
  await page.getByText('Sign out').last().click();
  await signInForm(page).waitFor({ timeout: 30000 });
  const after = await seen(page);
  after.freshDocument = !(await page.evaluate(() => window.__beforeSignOut === true));
  const steps = [];
  for (let i = 1; i <= historyLength + 1; i += 1) {
    const before = page.url();
    await page.goBack({ waitUntil: 'load', timeout: 10000 }).catch(() => undefined);
    const now = await seen(page);
    steps.push(now);
    if (page.url() === before || page.url() === 'about:blank') break;
  }
  results.signOut.afterSignOut = after;
  results.signOut.backAfterSignOut = steps;
  results.signOut.backAfterSignOutLog = [...log];
  await context.close();
}

// --- 3. Signed out, leave the site, press Back: the restored page must show no data --------
{
  const { context, page, log } = await open(1280, 900);
  await page.goto(APP + '/', { waitUntil: 'networkidle' });
  await signIn(page);
  await go(page, `/accounts/${accountId}`, /^Showing 50 of \d+$/);
  await go(page, '/settings', 'Sign out');
  await page.getByText('Sign out').last().click();
  await signInForm(page).waitFor({ timeout: 30000 });
  await page.goto(ELSEWHERE, { waitUntil: 'load' });
  log.length = 0;
  const steps = [];
  for (let i = 0; i < 4; i += 1) {
    const before = page.url();
    await page.goBack({ waitUntil: 'load', timeout: 10000 }).catch(() => undefined);
    steps.push(await seen(page));
    if (page.url() === before || page.url() === 'about:blank') break;
  }
  results.signOut.leaveAfterSignOutThenBack = steps;
  results.signOut.leaveAfterSignOutThenBackLog = [...log];
  await context.close();
}

// --- 4. Still signed in, leave the site, press Back: the member stays signed in ------------
{
  const { context, page, log } = await open(1280, 900);
  await page.goto(APP + '/', { waitUntil: 'networkidle' });
  await signIn(page);
  await go(page, `/accounts/${accountId}`, /^Showing 50 of \d+$/);
  await page.goto(ELSEWHERE, { waitUntil: 'load' });
  log.length = 0;
  await page.goBack({ waitUntil: 'load', timeout: 10000 }).catch(() => undefined);
  const back = await seen(page);
  back.accountListVisible = await page.getByText(/^Showing 50 of \d+$/).isVisible().catch(() => false);
  await page.screenshot({ path: `${out}/back-while-signed-in-desktop-1280.png` });
  results.signOut.leaveWhileSignedInThenBack = back;
  results.signOut.leaveWhileSignedInThenBackLog = [...log];
  await context.close();
}

await browser.close();
writeFileSync(`${out}/results.json`, JSON.stringify(results, null, 2) + '\n');
console.log(JSON.stringify(results, null, 2));
