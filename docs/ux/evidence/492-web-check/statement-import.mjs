// Web check for #492 (S7 statement import), synthetic member and the committed synthetic DKB
// fixture. Usage: TMW_EMAIL=... TMW_PASSWORD=... node statement-import.mjs <fixture.csv> <outDir>
// Expects a member whose workspace has a "DKB" portfolio with a EUR "Giro" CASH account.
import { chromium } from 'playwright-core';
import AxeBuilder from '@axe-core/playwright';
import { mkdirSync } from 'node:fs';

const [fixture, out] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const API = 'http://localhost:8080';
const APP = 'http://localhost:8081';
const EMAIL = process.env.TMW_EMAIL;
const PASSWORD = process.env.TMW_PASSWORD;
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const results = {};

async function api(method, path, token) {
  const res = await fetch(API + path, {
    method,
    headers: { authorization: 'Bearer ' + token },
  });
  return { status: res.status, json: await res.json().catch(() => null) };
}

async function signIn(page) {
  await page.goto(APP + '/', { waitUntil: 'networkidle' });
  await page.getByLabel(/^(Email|E-Mail)$/).fill(EMAIL);
  await page.getByLabel(/^(Password|Passwort)$/).fill(PASSWORD);
  await page.getByRole('button', { name: /^(Sign in|Anmelden)$/ }).last().click();
  await page.getByText(/Good (morning|afternoon|evening)/).first().waitFor({ timeout: 30000 });
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

// RN-web scrolls an inner div: unroll it for a full-page screenshot.
async function shot(page, name) {
  await page.addStyleTag({
    content: '* { overflow: visible !important; } html, body, #root { height: auto !important; }',
  });
  await page.screenshot({ path: `${out}/${name}.png`, fullPage: true });
}

function watch(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push('console: ' + m.text().slice(0, 200)));
  return errors;
}

const writes = [];
function record(page) {
  page.on('request', (req) => {
    if (/\/imports/.test(req.url()) && req.method() !== 'GET') {
      writes.push(`${req.method()} ${req.url().replace(API, '')} if-match=${req.headers()['if-match'] ?? '-'}`);
    }
  });
}

async function importOnce(page, label) {
  // The web overview header entry, then the account picker.
  await page.getByRole('button', { name: 'Import statement' }).first().click();
  await page.getByRole('radio', { name: 'DKB · Giro' }).click();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Choose file' }).click();
  await (await chooser).setFiles(fixture);
  const commit = page.getByRole('button', { name: /^Import \d+ transactions?$/ });
  await commit.waitFor({ timeout: 30000 });
  const confirm = page.getByRole('button', { name: 'Yes, this is my account' });
  results[`${label}.confirmRequired`] = (await confirm.count()) > 0;
  results[`${label}.disabledBeforeConfirm`] = await commit.getAttribute('aria-disabled');
  if (results[`${label}.confirmRequired`]) {
    await confirm.click();
    await confirm.waitFor({ state: 'detached', timeout: 15000 });
  }
  results[`${label}.commitLabel`] = await commit.textContent();
  results[`${label}.commitDisabled`] = await commit.getAttribute('aria-disabled');
  results[`${label}.text`] = (await page.locator('body').innerText()).slice(0, 3000);
  return commit;
}

const desktop = await browser.newContext({ locale: 'en-US', viewport: { width: 1280, height: 900 } });
const page = await desktop.newPage();
const errors = watch(page);
record(page);
await signIn(page);
const token = await page.evaluate(() => null);

// 1. First upload: straight to the review (PARSED), then commit.
let commit = await importOnce(page, 'first');
results['first.axe'] = await axe(page);
await shot(page, 'review-first-desktop');
const download = page.waitForEvent('download', { timeout: 5000 }).catch(() => null);
const errorsButton = page.getByRole('button', { name: 'Download error rows' });
if (await errorsButton.count()) {
  await errorsButton.click();
  const file = await download;
  results['first.errorsDownload'] = file ? file.suggestedFilename() : 'none';
}
await commit.click();
await page.getByText(/transactions? imported into Giro\./).waitFor({ timeout: 30000 });
results['done.text'] = await page.getByText(/imported into Giro/).textContent();
await shot(page, 'done-desktop');

// 2. The same file again: the "already imported" warning, every row a duplicate and off.
await page.getByRole('button', { name: 'Import another file' }).click();
await page.evaluate(() => {
  history.pushState({}, '', '/overview');
  dispatchEvent(new PopStateEvent('popstate'));
});
commit = await importOnce(page, 'second');
results['second.switches'] = await page
  .getByRole('switch')
  .evaluateAll((els) => els.map((e) => e.getAttribute('aria-checked') ?? String(e.checked)));
results['second.axe'] = await axe(page);
await shot(page, 'review-second-desktop');

// Phone width, light and dark, of the same review.
for (const scheme of ['light', 'dark']) {
  await page.emulateMedia({ colorScheme: scheme });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(800);
  results[`phone.${scheme}.hScroll`] = await page.evaluate(
    () => document.scrollingElement.scrollWidth > document.scrollingElement.clientWidth + 1,
  );
  await shot(page, `review-second-phone-${scheme}`);
}
await page.emulateMedia({ colorScheme: 'light' });
await page.setViewportSize({ width: 1280, height: 900 });

// 3. Cancel: discard the second batch, nothing is imported.
await page.getByRole('button', { name: 'Cancel' }).click();
await page.getByRole('button', { name: 'Discard' }).last().click();
await page.getByText(/Good (morning|afternoon|evening)/).first().waitFor({ timeout: 30000 });

results.writes = writes;
results.errors = errors;
results.token = token;
console.log(JSON.stringify(results, null, 2));
await browser.close();
