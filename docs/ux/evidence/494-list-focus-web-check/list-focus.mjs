// Web check for #494 list focus (S3): where keyboard focus goes after "Load more" and after a
// category save under the Uncategorised filter. Runs on a fresh stack seeded by
// ../494-web-check/seed.mjs (synthetic data only; four uncategorised bookings: Coop, Migros, SBB,
// Amazon.de). Every save changes data, so reseed before each run.
// Usage: TMW_EMAIL=... TMW_PASSWORD=... TMW_APP=http://localhost:8082 \
//   node list-focus.mjs <accountId> <desktop|phone> <out.json>
// Needs playwright-core (scratch folder) and the installed Google Chrome.
import { chromium } from 'playwright-core';
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';

const [accountId, layout, outFile] = process.argv.slice(2);
const APP = process.env.TMW_APP ?? 'http://localhost:8082';
const viewport = layout === 'phone' ? { width: 390, height: 844 } : { width: 1280, height: 900 };
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ locale: 'en-GB', viewport });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => m.type() === 'error' && errors.push('console: ' + m.text().slice(0, 200)));

// The session lives in memory: sign in at /, then navigate client-side.
await page.goto(APP + '/', { waitUntil: 'networkidle' });
await page.getByLabel(/^(Email|E-Mail)$/).fill(process.env.TMW_EMAIL);
await page.getByLabel(/^(Password|Passwort)$/).fill(process.env.TMW_PASSWORD);
await page.getByRole('button', { name: /^(Sign in|Anmelden)$/ }).last().click();
await page.getByText(/Good (morning|afternoon|evening)/).first().waitFor({ timeout: 30000 });
await page.evaluate((path) => {
  history.pushState({}, '', path);
  dispatchEvent(new PopStateEvent('popstate'));
}, `/accounts/${accountId}`);
await page.getByText(/^Showing 50 of \d+$/).waitFor({ timeout: 30000 });

/** The focused element: its id, a short text and whether it is inside the viewport. */
const focused = () =>
  page.evaluate(() => {
    const el = document.activeElement;
    const box = el?.getBoundingClientRect();
    return {
      id: el?.id || null,
      text: (el?.innerText || el?.getAttribute('aria-label') || el?.tagName || '').slice(0, 60),
      heading: el?.getAttribute('role') === 'heading' || /^H\d$/.test(el?.tagName ?? ''),
      visible: !!box && box.top >= 0 && box.bottom <= innerHeight,
    };
  });
/** Waits until focus leaves the closing dialog and the page body. */
const settledFocus = async () => {
  await page.waitForFunction(
    () =>
      document.activeElement &&
      document.activeElement !== document.body &&
      !document.activeElement.closest('[role=dialog],[aria-modal=true]'),
    null,
    { timeout: 10000 },
  );
  await page.waitForTimeout(300);
  return focused();
};
const rowIds = () =>
  page.evaluate(() => [...document.querySelectorAll('[id^="transaction-"]')].map((e) => e.id));

const results = { layout, viewport };

// 1. Load more with the keyboard: focus moves to the first new row and shows it.
const before = await rowIds();
await page.getByRole('button', { name: 'Load more' }).focus();
await page.keyboard.press('Enter');
await page.getByText(/^Showing (\d+) of \1$/).waitFor();
await page.waitForTimeout(300);
const after = await rowIds();
results.loadMore = { before: before.length, after: after.length, focus: await focused() };
assert.equal(results.loadMore.focus.id, after[before.length], 'focus on the first new row');
assert(results.loadMore.focus.visible, 'the first new row is scrolled into view');

// 2. Category saves under the Uncategorised filter, all with the keyboard.
await page.getByRole('radio', { name: /^Uncategorised \d+$/ }).click();
await page.getByText(/^Showing 4 of 4$/).waitFor();
const saveCategory = async (title) => {
  const chip = page
    .getByRole('button', { name: /^Change category: Uncategorised$/ })
    .nth((await uncategorisedTitles()).indexOf(title));
  await chip.focus();
  await page.keyboard.press('Enter');
  const groceries = page.getByRole('dialog').getByRole('button', { name: 'Groceries', exact: true });
  await groceries.waitFor();
  await page.waitForTimeout(500); // the Modal moves focus once it is open
  await groceries.focus();
  await page.keyboard.press('Enter');
  return settledFocus();
};
/** Titles of the uncategorised rows, in display order (one chip per row). */
const uncategorisedTitles = () =>
  page.evaluate(() =>
    [...document.querySelectorAll('[id^="transaction-"]')].map((e) =>
      (e.innerText || e.getAttribute('aria-label') || '').trim(),
    ),
  ).then((texts) =>
    texts.map((t) => ['Coop', 'Migros', 'SBB', 'Amazon.de'].find((name) => t.includes(name))),
  );

results.order = await uncategorisedTitles();
assert.deepEqual(results.order, ['Coop', 'Migros', 'SBB', 'Amazon.de']);

// Middle row leaves: focus on the row that followed it.
results.middle = await saveCategory('Migros');
assert.match(results.middle.text, /SBB/, 'middle row saved → next row');
await page.getByText(/^Showing 3 of 3$/).waitFor();

// Last row leaves: focus on the row before it.
results.last = await saveCategory('Amazon.de');
assert.match(results.last.text, /SBB/, 'last row saved → previous row');
await page.getByText(/^Showing 2 of 2$/).waitFor();

// First row leaves: focus on the row that followed it.
results.first = await saveCategory('Coop');
assert.match(results.first.text, /SBB/, 'first row saved → next row');
await page.getByText(/^Showing 1 of 1$/).waitFor();

// The only row leaves: the filter falls back to All and focus goes to the account heading.
results.only = await saveCategory('SBB');
assert(results.only.heading && /Girokonto/.test(results.only.text), 'empty list → heading');
results.filterAfterOnly = await page.getByRole('radio', { name: 'All', exact: true }).isChecked();

results.errors = errors;
assert.deepEqual(errors, []);
writeFileSync(outFile, JSON.stringify(results, null, 2));
console.log(JSON.stringify(results, null, 2));
await browser.close();
