// Web check for #494 (S3 account detail) on a stack seeded by seed.mjs (synthetic data only).
// Usage: TMW_EMAIL=... TMW_PASSWORD=... TMW_API=http://localhost:8090 TMW_APP=http://localhost:8082 \
//   node account-detail.mjs <accountId> <outDir>
// Needs playwright-core and @axe-core/playwright (scratch folder) and the installed Google Chrome.
import { chromium } from 'playwright-core';
import AxeBuilder from '@axe-core/playwright';
import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';

const [accountId, out] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const API = process.env.TMW_API ?? 'http://localhost:8090';
const APP = process.env.TMW_APP ?? 'http://localhost:8082';
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const results = {};

async function open({ width, height, colorScheme = 'light', deviceScaleFactor = 1 }) {
  const context = await browser.newContext({
    locale: 'en-GB',
    viewport: { width, height },
    colorScheme,
    deviceScaleFactor,
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push('console: ' + m.text().slice(0, 200)));
  const writes = [];
  page.on('request', (req) => {
    if (req.url().startsWith(API) && req.method() !== 'GET') {
      writes.push(`${req.method()} ${req.url().replace(API, '')} if-match=${req.headers()['if-match'] ?? '-'}`);
    }
  });
  const lists = [];
  page.on('request', (req) => {
    if (req.method() === 'GET' && /\/transactions\?/.test(req.url()) && /size=50/.test(req.url())) {
      lists.push(new URL(req.url()).search);
    }
  });
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
  return { context, page, errors, writes, lists };
}

async function axe(page) {
  const r = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  return r.violations.map(
    (v) => `${v.id}(${v.impact}) x${v.nodes.length}: ${v.nodes.slice(0, 2).map((n) => n.target.join(' ')).join(' | ')}`,
  );
}

/** Horizontal page scroll: the document or RN-web's scrolling div wider than the viewport. */
const hScroll = (page) =>
  page.evaluate(() => {
    const scrollers = [document.scrollingElement, ...document.querySelectorAll('div')].filter(
      (el) => el && getComputedStyle(el).overflowX !== 'visible' && el.scrollWidth > el.clientWidth + 1,
    );
    return scrollers.map((el) => `${el.tagName}${el.querySelector(':scope > table') ? '(table)' : ''} ${el.scrollWidth}>${el.clientWidth}`);
  });

// RN-web scrolls an inner div: unroll it for a full-page screenshot.
async function shot(page, name) {
  await page.addStyleTag({
    content: '* { overflow: visible !important; } html, body, #root { height: auto !important; }',
  });
  await page.screenshot({ path: `${out}/${name}.png`, fullPage: true });
}

const text = (page) => page.evaluate(() => document.body.innerText);

// 1. Phone 390 × 844, light: content, semantics, axe, screenshot.
{
  const { context, page, errors } = await open({ width: 390, height: 844 });
  const body = await text(page);
  results.phone = {
    context: /DKB · Current account · EUR/.test(body),
    reconciliation: (body.match(/⚠ Differs from the statement of [^\n]+/) ?? [''])[0],
    chip: (body.match(/Uncategorised \d+/) ?? [''])[0],
    banner: /need a category/.test(body) && /never hidden in “Other”/.test(body),
    tables: await page.locator('table').count(),
    voided: await page.getByText('Voided', { exact: true }).count(),
    reversal: (body.match(/Reversal of [^\n]+|Reversal\b/) ?? [''])[0],
    internalTransfer: /Internal transfer · excluded/.test(body),
    rawCodes: body.match(/\b(EXPENSE|INCOME|TRANSFER|CASH|CREDIT_CARD_PURCHASE)\b/g) ?? [],
    hScroll: await hScroll(page),
    axe: await axe(page),
  };
  await shot(page, 'account-phone-light');
  results.phone.errors = errors;
  await context.close();
}

// 2. Phone dark, 320 px and 200 % zoom (1280 px at 200 % = 640 CSS px).
for (const [name, options] of [
  ['account-phone-dark', { width: 390, height: 844, colorScheme: 'dark' }],
  ['account-320', { width: 320, height: 720 }],
  ['account-zoom-200', { width: 640, height: 450, deviceScaleFactor: 2 }],
]) {
  const { context, page, errors } = await open(options);
  results[name] = { hScroll: await hScroll(page), errors };
  if (name === 'account-phone-dark') results[name].axe = await axe(page);
  await shot(page, name);
  await context.close();
}

// 3. Desktop keyboard navigation in the normal shell scroll container.
{
  const { context, page } = await open({ width: 1280, height: 900 });
  results.desktop = {
    tableHeaders: await page.locator('table th[scope=col]').allInnerTexts(),
    hScroll: await hScroll(page),
    axe: await axe(page),
  };
  const transactionButtons = page.locator(
    'table tbody button[data-transaction-open]',
  );
  const first = transactionButtons.first();
  const accountScroll = await first.evaluateHandle((button) => {
    // The table wrapper can scroll horizontally. Find the ancestor that actually scrolls vertically.
    for (let node = button.parentElement; node; node = node.parentElement) {
      if (
        /^(auto|scroll)$/.test(getComputedStyle(node).overflowY) &&
        node.scrollHeight > node.clientHeight
      ) {
        return node;
      }
    }
    throw new Error('Account scroll container not found');
  });
  const scrollTop = () => accountScroll.evaluate((node) => node.scrollTop);
  const assertVisibleAndFocused = async (button) => {
    assert(
      await button.evaluate((node, scroller) => {
        const bounds = node.getBoundingClientRect();
        const top = scroller.getBoundingClientRect().top + scroller.clientTop;
        return (
          document.activeElement === node &&
          bounds.top >= top - 1 &&
          bounds.bottom <= top + scroller.clientHeight + 1
        );
      }, accountScroll),
    );
  };

  await first.focus();
  const beforeDown = await scrollTop();
  for (let row = 0; row < 20; row++) await page.keyboard.press('ArrowDown');
  await assertVisibleAndFocused(transactionButtons.nth(20));
  assert(
    (await scrollTop()) > beforeDown,
    'ArrowDown must scroll the account container',
  );
  const previous = transactionButtons.nth(19);
  await previous.evaluate((button, scroller) => {
    const top = scroller.getBoundingClientRect().top + scroller.clientTop;
    scroller.scrollTop += button.getBoundingClientRect().bottom - top + 4;
    if (button.getBoundingClientRect().bottom > top)
      throw new Error('ArrowUp target is already visible');
  }, accountScroll);
  const beforeUp = await scrollTop();
  await page.keyboard.press('ArrowUp');
  await assertVisibleAndFocused(previous);
  assert(
    (await scrollTop()) < beforeUp,
    'ArrowUp must scroll the account container',
  );

  // Tab from the last control in row 19 to the first control in row 20 below the viewport.
  const next = transactionButtons.nth(20);
  const previousRow = page.locator('table tbody tr').nth(19);
  await previousRow.locator('button:enabled').last().focus();
  await next.evaluate((button, scroller) => {
    const bottom =
      scroller.getBoundingClientRect().top +
      scroller.clientTop +
      scroller.clientHeight;
    scroller.scrollTop += button.getBoundingClientRect().top - bottom - 4;
    if (button.getBoundingClientRect().top < bottom)
      throw new Error('Tab target is already visible');
  }, accountScroll);
  const beforeTab = await scrollTop();
  await page.keyboard.press('Tab');
  await assertVisibleAndFocused(next);
  assert(
    (await scrollTop()) > beforeTab,
    'Tab must scroll the account container',
  );

  const opener = page.getByRole('button', {
    name: 'Synthetic corrected purchase',
    exact: true,
  });
  const panel = page.getByTestId('shell-detail-panel');
  const accountDetail = page.getByTestId('account-detail');
  const back = panel.getByRole('button', { name: 'Back to account' });
  const related = panel.getByRole('link', {
    name: 'Open the previous transaction',
  });
  await opener.press('Enter');
  await related.waitFor();
  assert(await accountDetail.isVisible(), 'Account detail must remain visible beside S4');
  await page.waitForFunction(() =>
    Boolean(
      document.activeElement?.closest('[data-testid="shell-detail-panel"]'),
    ),
  );
  assert.equal(await opener.getAttribute('aria-current'), 'true');
  await related.press('Enter');
  await panel
    .getByText(/^Voided on/)
    .first()
    .waitFor();
  assert.equal(await opener.getAttribute('aria-current'), null);
  await back.press('Enter');
  await panel.waitFor({ state: 'detached' });
  await assertVisibleAndFocused(opener);

  // The filter unmounts the opening row while S4 remains open.
  await opener.press('Enter');
  await related.waitFor();
  await page.getByRole('radio', { name: /^Uncategorised \d+$/ }).press('Enter');
  await opener.waitFor({ state: 'detached' });
  await back.press('Enter');
  await panel.waitFor({ state: 'detached' });
  const heading = page.getByRole('heading', {
    name: 'Account detail',
    exact: true,
  });
  assert(await heading.evaluate((node) => document.activeElement === node));
  results.desktop.detailPanel = {
    arrowsAndTabScrolledAccount: true,
    focusReturnedAfterRelatedTransaction: true,
    focusedHeadingAfterFiltering: true,
    accountContextPreserved: true,
  };

  await page.getByRole('radio', { name: 'All', exact: true }).press('Enter');
  await page.getByText(/^Showing 50 of \d+$/).waitFor();
  await shot(page, 'account-desktop');
  await context.close();
}
{
  const { context, page, errors, writes, lists } = await open({ width: 1280, height: 900 });
  const d = (results.flows = {});

  await page.getByRole('button', { name: 'Load more' }).click();
  await page.getByText(/^Showing (\d+) of \1$/).waitFor();
  d.loadMore = (await text(page)).match(/Showing \d+ of \d+/)[0];

  // Keyboard: open the ⋯ menu with Enter, close with Escape, focus returns to the trigger.
  const actions = page.getByRole('button', { name: 'Account actions' });
  await actions.focus();
  await page.keyboard.press('Enter');
  await page.getByRole('button', { name: 'Import statement' }).waitFor();
  await page.waitForTimeout(500); // the Modal moves focus once it is open
  d.dialogFocus = await page.evaluate(() => ({
    text: document.activeElement?.textContent?.slice(0, 40),
    inDialog: !!document.activeElement?.closest('[role=dialog],[aria-modal=true]'),
  }));
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Import statement' }).waitFor({ state: 'detached', timeout: 5000 }).catch(() => {});
  await page.waitForTimeout(500);
  d.focusAfterClose = await page.evaluate(() => document.activeElement?.getAttribute('aria-label') ?? document.activeElement?.tagName);

  // Uncategorised: banner → filter → change one category → the server count drops.
  const before = (await text(page)).match(/Uncategorised (\d+)/)[1];
  await page.getByText(/need a category/).click();
  await page.getByText(new RegExp(`^Showing ${before} of ${before}$`)).waitFor();
  await page.getByRole('button', { name: /^Change category: Uncategorised$/ }).first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Groceries', exact: true }).click();
  await page.getByText(new RegExp(`Uncategorised ${Number(before) - 1}\\b`)).waitFor();
  d.uncategorised = `${before} -> ${Number(before) - 1}`;
  d.listAfterCategory = lists.at(-1);
  await page.getByRole('radio', { name: 'All' }).click();
  await page.getByText(/^Showing 50 of \d+$/).waitFor();

  // Sort by largest amount: a server sort, back to page 0.
  await page.getByRole('button', { name: /Newest first/ }).click();
  await page.getByRole('radio', { name: 'Largest amount' }).click();
  await page.waitForTimeout(1500);
  d.sortRequest = lists.at(-1);

  // Archive with If-Match, then restore.
  await page.getByRole('button', { name: 'Account actions' }).click();
  await page.getByRole('button', { name: 'Archive account' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Archive account' }).click();
  await page.getByText(/Restore the account to change it\./).first().waitFor();
  d.archivedBanner = (await text(page)).match(/Archived on [^\n]+/)?.[0];
  d.categoryDisabled = await page.getByRole('button', { name: /^Change category:/ }).count();
  await page.getByRole('button', { name: 'Account actions' }).click();
  d.importDisabled = await page.getByRole('button', { name: 'Import statement' }).isDisabled();
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);
  await page.getByRole('button', { name: 'Restore account' }).click();
  await page.getByText(/Restore the account to change it\./).first().waitFor({ state: 'detached' });
  await shot(page, 'account-desktop-after-flows');
  d.writes = writes;
  d.errors = errors;
  await context.close();
}

await browser.close();
console.log(JSON.stringify(results, null, 2));

// Critical accessibility and correctness regressions must fail this check automatically.
const failures = [];
for (const target of ['phone', 'account-phone-dark', 'desktop']) {
  for (const violation of results[target].axe ?? []) {
    failures.push(`${target}: axe ${violation}`);
  }
}
for (const [target, result] of Object.entries(results)) {
  if (Array.isArray(result.hScroll) && result.hScroll.length) {
    failures.push(`${target}: horizontal overflow: ${result.hScroll.join(', ')}`);
  }
  if (Array.isArray(result.errors) && result.errors.length) {
    failures.push(`${target}: browser errors: ${result.errors.join('; ')}`);
  }
}
if (!results.phone.context || results.phone.tables !== 0 || results.phone.rawCodes.length) {
  failures.push('phone: broken account context, unexpected table or raw API codes');
}
if (results.desktop.tableHeaders.join('|') !== 'Date|Description|Category|Amount') {
  failures.push('desktop: semantic transaction table headers are missing');
}
if (
  !results.desktop.detailPanel?.arrowsAndTabScrolledAccount ||
  !results.desktop.detailPanel?.focusReturnedAfterRelatedTransaction ||
  !results.desktop.detailPanel?.focusedHeadingAfterFiltering ||
  !results.desktop.detailPanel?.accountContextPreserved
) {
  failures.push('desktop S4: keyboard scrolling, focus return or account context failed');
}
if (results.flows.importDisabled !== true || !results.flows.writes.length) {
  failures.push('flows: archive/import write controls did not behave as expected');
}
if (failures.length) {
  console.error(failures.join('\n'));
  process.exitCode = 1;
}
