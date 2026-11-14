// #496 / PR #573: signed-in web check of the holdings navigation plus axe on the affected screens.
// Runs against a local stack seeded with scripts/seed_demo.py and a depot with six holdings (see
// README.md). Needs playwright-core and @axe-core/playwright in a scratch folder and the system
// Chrome. Credentials come from the environment, never from this file:
//   TMW_EMAIL=… TMW_PASSWORD=… APP=http://localhost:8084 OUT=<dir> node positions-web.mjs
import AxeBuilder from '@axe-core/playwright';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { chromium } from 'playwright-core';

const APP = process.env.APP ?? 'http://localhost:8084';
const OUT = process.env.OUT ?? '.';
const { TMW_EMAIL, TMW_PASSWORD } = process.env;
if (!TMW_EMAIL || !TMW_PASSWORD) throw new Error('Set TMW_EMAIL and TMW_PASSWORD');

const PORTFOLIO = 'Demo · Alpine Lantern Investments';
const DEPOT = 'Demo · Alpine Lantern Depot';
const HOLDINGS = [
  'Alpine Lantern Demo Equity Fund',
  'Cloudberry Demo Bond Fund',
  'Paper Moon Demo Tech Share',
  'Alpine Demo Real Estate Fund',
  'Lantern Demo Gold Tracker',
  'Cloudberry Demo Dividend Share',
];
const VIEWPORTS = { desktop: { width: 1280, height: 900 }, phone: { width: 390, height: 844 } };

const results = [];
const axeReport = {};
const check = (name, pass, detail = '') => results.push({ name, pass: Boolean(pass), detail });

async function axe(page, key) {
  const { violations } = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  axeReport[key] = violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    nodes: v.nodes.length,
    targets: v.nodes.slice(0, 3).map((n) => n.target.join(' ')),
  }));
}

// Wide screens show holdings as a table of links, phones as a list of buttons.
const holding = (page, name) =>
  page
    .getByRole('link', { name: new RegExp(`^${name}`) })
    .or(page.getByRole('button', { name: new RegExp(`^${name}`) }))
    .first();

const radioChecked = async (page, name) =>
  (await page.getByRole('radio', { name: new RegExp(`^${name}`) }).getAttribute('aria-checked')) ===
  'true';

async function holdingsVisible(page) {
  const seen = [];
  for (const name of HOLDINGS) {
    if (await page.getByText(name, { exact: true }).first().isVisible().catch(() => false)) {
      seen.push(name);
    }
  }
  return seen;
}

// In-app navigation only: the web session lives in memory, so a full page load signs out.
async function openPortfolio(page) {
  if (!/\/overview$/.test(new URL(page.url()).pathname)) {
    await page.getByRole('link', { name: 'Overview', exact: true }).click();
  }
  await page.getByRole('button', { name: PORTFOLIO, exact: true }).first().click();
  await page.getByRole('button', { name: new RegExp(`^${DEPOT}`) }).waitFor();
  await holding(page, HOLDINGS[0]).waitFor();
}

// react-native-web scrolls an inner view, so fullPage shows only the viewport: bring the part
// under test into view first.
async function shot(page, focus, file) {
  await focus.scrollIntoViewIfNeeded();
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(OUT, file) });
}

async function expectPositions(page, label, file) {
  await page.waitForURL(/\/accounts\/[^/?]+\?tab=positions$/);
  await page.getByRole('radio', { name: /^Positions/ }).waitFor();
  await page.getByText(HOLDINGS[5], { exact: true }).first().waitFor();
  const seen = await holdingsVisible(page);
  check(`${label}: opens the depot`, (await page.getByText(DEPOT).count()) > 0);
  check(`${label}: Positions selected`, await radioChecked(page, 'Positions'));
  check(`${label}: all 6 holdings listed`, seen.length === 6, `${seen.length}/6`);
  await shot(page, page.getByRole('radio', { name: /^Positions/ }), file);
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
  for (const [viewport, size] of Object.entries(VIEWPORTS)) {
    const context = await browser.newContext({ locale: 'en-GB', viewport: size });
    const page = await context.newPage();
    const errors = [];
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text().slice(0, 160)));
    await page.goto(`${APP}/`);
    await page.getByLabel('Email').first().fill(TMW_EMAIL);
    await page.getByLabel('Password').first().fill(TMW_PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await page.getByRole('button', { name: 'Sign in' }).waitFor({ state: 'detached' });

    // Portfolio View: the depot card shows five holdings and "Show all 6 positions".
    await openPortfolio(page);
    const showAll = page.getByRole('button', { name: 'Show all 6 positions' });
    check(`${viewport} portfolio: "Show all 6 positions" shown`, await showAll.isVisible());
    await shot(page, showAll, `${viewport}-1-portfolio.png`);
    await axe(page, `${viewport} Portfolio View`);

    // 1. Tap one holding.
    await holding(page, HOLDINGS[2]).click();
    await expectPositions(page, `${viewport} tap holding`, `${viewport}-2-holding-positions.png`);
    await axe(page, `${viewport} Account Detail · Positions`);

    // 2. Same depot via "Show all positions".
    await openPortfolio(page);
    await page.getByRole('button', { name: 'Show all 6 positions' }).click();
    await expectPositions(page, `${viewport} show all`, `${viewport}-3-show-all-positions.png`);

    // 3. The account row opens Activity; the tabs switch both ways.
    await openPortfolio(page);
    await page.getByRole('button', { name: new RegExp(`^${DEPOT}`) }).click();
    await page.waitForURL(/\/accounts\/[^/?]+$/);
    await page.getByRole('radio', { name: /^Activity/ }).waitFor();
    check(`${viewport} account row: Activity selected`, await radioChecked(page, 'Activity'));
    check(`${viewport} account row: no holdings listed`, (await holdingsVisible(page)).length === 0);
    await shot(page, page.getByRole('radio', { name: /^Activity/ }), `${viewport}-4-row-activity.png`);
    await axe(page, `${viewport} Account Detail · Activity`);
    await page.getByRole('radio', { name: /^Positions/ }).click();
    await page.getByText(HOLDINGS[5], { exact: true }).first().waitFor();
    check(`${viewport} switch to Positions`, (await holdingsVisible(page)).length === 6);
    await page.getByRole('radio', { name: /^Activity/ }).click();
    await page.waitForTimeout(800);
    check(
      `${viewport} switch back to Activity`,
      (await radioChecked(page, 'Activity')) && (await holdingsVisible(page)).length === 0,
    );
    await shot(page, page.getByRole('radio', { name: /^Activity/ }), `${viewport}-5-back-to-activity.png`);
    check(`${viewport}: no console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await context.close();
  }
} catch (error) {
  check('script error', false, error.message.split('\n')[0]);
} finally {
  await browser.close();
  writeFileSync(join(OUT, 'axe-results.json'), `${JSON.stringify(axeReport, null, 2)}\n`);
  for (const r of results) console.log(`${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? ` — ${r.detail}` : ''}`);
  for (const [key, violations] of Object.entries(axeReport)) {
    console.log(`axe ${key}: ${violations.length === 0 ? 'no violations' : ''}`);
    for (const v of violations) console.log(`  ${v.impact} ${v.id} (${v.nodes}) ${v.targets.join(' ; ')}`);
  }
}
