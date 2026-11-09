// Web check for #490 (overview name: first-run step and Settings), synthetic member.
// Usage: TMW_EMAIL=... TMW_PASSWORD=... node overview-name.mjs <outDir>
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';

const out = process.argv[2];
const API = 'http://localhost:8080';
const EMAIL = process.env.TMW_EMAIL;
const PASSWORD = process.env.TMW_PASSWORD;
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const results = [];

async function api(method, path, { token, body, ifMatch } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: 'Bearer ' + token } : {}),
      ...(ifMatch ? { 'if-match': ifMatch } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, etag: res.headers.get('etag'), json: await res.json().catch(() => null) };
}

async function signIn(page) {
  await page.goto('http://localhost:8081/', { waitUntil: 'networkidle' });
  await page.getByLabel(/^(Email|E-Mail)$/).fill(EMAIL);
  await page.getByLabel(/^(Password|Passwort)$/).fill(PASSWORD);
  await page.getByRole('button', { name: /^(Sign in|Anmelden)$/ }).last().click();
}

function go(page, path) {
  return page.evaluate((p) => {
    history.pushState({}, '', p);
    dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

async function axe(page) {
  const r = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  return r.violations.map(
    (v) =>
      `${v.id}(${v.impact}) x${v.nodes.length}: ${v.nodes
        .slice(0, 3)
        .map((n) => n.target.join(' '))
        .join(' | ')}`,
  );
}

const facts = (page) =>
  page.evaluate(() => ({
    hScroll: document.scrollingElement.scrollWidth > document.scrollingElement.clientWidth + 1,
    tabs: [...document.querySelectorAll('[role=tab]')].map(
      (e) => `${e.getAttribute('aria-label') ?? e.textContent}|selected=${e.getAttribute('aria-selected')}`,
    ),
    saveDisabled: [...document.querySelectorAll('[role=button]')]
      .filter((e) => /^(Save|Continue|Speichern|Weiter)$/.test(e.textContent.trim()))
      .map((e) => `${e.textContent.trim()}:${e.getAttribute('aria-disabled')}`),
    workspaceWord: /workspace/i.test(document.body.innerText),
  }));

function context(viewport, scheme, dsf = 1) {
  return browser.newContext({ locale: 'en-US', viewport, deviceScaleFactor: dsf, colorScheme: scheme });
}

function watch(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push('console: ' + m.text().slice(0, 200)));
  return errors;
}

// 1. The first-run step, light and dark, phone and desktop (fresh browser = no device marker).
for (const scheme of ['light', 'dark'])
  for (const [name, viewport, dsf] of [
    ['phone', { width: 390, height: 844 }, 2],
    ['desktop', { width: 1280, height: 900 }, 1],
  ]) {
    const ctx = await context(viewport, scheme, dsf);
    const page = await ctx.newPage();
    const errors = watch(page);
    await signIn(page);
    await page.getByText('Name your overview').waitFor({ timeout: 20000 });
    await page.waitForTimeout(500);
    const empty = await facts(page);
    await page.getByLabel('Name of your overview').fill('Family Hug');
    await page.waitForTimeout(300);
    const typed = await facts(page);
    const a11y = scheme === 'light' ? await axe(page) : null;
    await page.screenshot({ path: `${out}/setup-${scheme}-${name}.png`, fullPage: true });
    results.push({ tag: `setup-${scheme}-${name}`, empty, typed, axe: a11y, errors });
    await ctx.close();
  }

// 2. Too long: 61 characters disable Continue; 60 emoji do not.
{
  const ctx = await context({ width: 390, height: 844 }, 'light', 2);
  const page = await ctx.newPage();
  const errors = watch(page);
  await signIn(page);
  await page.getByText('Name your overview').waitFor({ timeout: 20000 });
  await page.getByLabel('Name of your overview').fill('x'.repeat(61));
  await page.waitForTimeout(300);
  const tooLong = { ...(await facts(page)), text: await page.getByText(/Use at most 60/).count() };
  await page.screenshot({ path: `${out}/setup-light-phone-too-long.png`, fullPage: true });
  await page.getByLabel('Name of your overview').fill('🏠'.repeat(60));
  await page.waitForTimeout(300);
  const emoji = {
    ...(await facts(page)),
    counter: await page.getByText(/of 60 characters/).innerText(),
  };
  // 3. Continue with a name: the overview opens and the first tab shows it.
  await page.getByLabel('Name of your overview').fill('Family Hug');
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByRole('tab', { name: 'Family Hug' }).first().waitFor({ timeout: 20000 });
  await page.waitForTimeout(500);
  const afterContinue = await facts(page);
  await page.screenshot({ path: `${out}/overview-after-continue-phone.png` });
  // Reload in the same browser: the device marker skips the step.
  await signIn(page);
  await page.waitForTimeout(2500);
  const stepAgain = await page.getByText('Name your overview').count();
  results.push({ tag: 'continue', tooLong, emoji, afterContinue, stepAgain, errors });

  // 4. Settings: row, edit sheet, conflict, Clear name.
  await go(page, '/settings');
  await page.getByRole('button', { name: 'Name of your overview' }).waitFor({ timeout: 20000 });
  const row = await page
    .getByRole('button', { name: 'Name of your overview' })
    .evaluate((e) => e.textContent);
  await page.getByRole('button', { name: 'Name of your overview' }).click();
  await page.getByRole('button', { name: 'Clear name' }).waitFor();
  await page.waitForTimeout(400);
  const sheetAxe = await axe(page);
  await page.screenshot({ path: `${out}/settings-sheet-phone.png` });

  // Another session renames meanwhile; this Save then meets a stale If-Match.
  const login = await api('POST', '/api/v1/auth/login', { body: { email: EMAIL, password: PASSWORD } });
  const token = login.json?.tokens?.accessToken;
  const current = await api('GET', '/api/v1/workspace', { token });
  const other = await api('PUT', '/api/v1/workspace/name', {
    token,
    ifMatch: current.etag,
    body: { name: 'Their name' },
  });
  await page.getByTestId('overview-name-field').fill('My name');
  await page.getByRole('button', { name: 'Save' }).click();
  await page.getByText(/Another member changed the name to/).waitFor({ timeout: 10000 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${out}/settings-conflict-phone.png` });
  const conflict = await page.evaluate(() => document.body.innerText.match(/Another member[^\n]*|Your unsaved[^\n]*/g));
  await page.getByRole('button', { name: 'Review and save again' }).click();
  await page.waitForTimeout(300);
  const keptDraft = await page.getByTestId('overview-name-field').inputValue();

  // Clear name sends null; the row and the first tab fall back to "All portfolios".
  await page.getByRole('button', { name: 'Clear name' }).click();
  await page.waitForTimeout(1200);
  const rowAfterClear = await page
    .getByRole('button', { name: 'Name of your overview' })
    .evaluate((e) => e.textContent);
  const serverAfterClear = (await api('GET', '/api/v1/workspace', { token })).json?.name;
  await go(page, '/overview');
  await page.getByRole('tab', { name: 'All portfolios' }).first().waitFor({ timeout: 20000 });
  const overviewAfterClear = await facts(page);
  results.push({
    tag: 'settings',
    row,
    sheetAxe,
    otherRename: other.status,
    conflict,
    keptDraft,
    rowAfterClear,
    serverAfterClear,
    overviewAfterClear,
    errors,
  });
  await ctx.close();
}

console.log(JSON.stringify(results, null, 2));
await browser.close();
