// PR #606 (#493): signed-in web check of the converted balance in portfolio account rows.
// Stack: own compose project + seed_demo.py + seed_606.py (CHF accounts in the EUR portfolio).
// Credentials from the environment only:
//   TMW_EMAIL=… TMW_PASSWORD=… LANG_CODE=en|de [LOCALE=de-DE|de-CH] APP=http://localhost:8100 OUT=<dir> \
//   node converted-balance-web.mjs
import AxeBuilder from '@axe-core/playwright';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { chromium } from 'playwright-core';

const APP = process.env.APP ?? 'http://localhost:8100';
const OUT = process.env.OUT ?? '.';
const LANG = process.env.LANG_CODE ?? 'en';
const TAG = process.env.LOCALE ?? LANG;
const { TMW_EMAIL, TMW_PASSWORD } = process.env;
if (!TMW_EMAIL || !TMW_PASSWORD) throw new Error('Set TMW_EMAIL and TMW_PASSWORD');

const T = {
  en: {
    approx: /approximately minus 108\.31 euros/, approxWord: /approximately/,
    unknown: 'Value unknown', hidden: 'hidden', hide: 'Hide amounts',
  },
  de: {
    approx: process.env.LOCALE === 'de-CH' ? /ungefähr minus 108\.31 Euro/ : /ungefähr minus 108,31 Euro/, approxWord: /ungefähr/,
    unknown: 'Wert unbekannt', hidden: 'verborgen', hide: 'Beträge verbergen',
  },
}[LANG];
const PORTFOLIO = 'Demo · Paper Moon Bank';
const FOREIGN = 'Demo · Glacier Franc Current';
const SAME = 'Demo · Paper Moon Current';
const UNKNOWN = 'Demo · Glacier Franc Savings';
const VIEWPORTS = LANG === 'en'
  ? { phone: { width: 390, height: 844 }, desktop: { width: 1280, height: 900 } }
  : { phone: { width: 390, height: 844 } };

const results = [];
const axeReport = {};
const check = (name, pass, detail = '') => results.push({ name: `${TAG} ${name}`, pass: Boolean(pass), detail });
const rowButton = (page, name) => page.getByRole('button', { name: new RegExp(`^${name},`) });
const label = (page, name) => rowButton(page, name).getAttribute('aria-label');
// The row container (testID account-row-<id>) holds the button and the status badge.
const rowBox = (page, name) => page.locator('[data-testid^="account-row-"]').filter({ has: rowButton(page, name) });

async function axe(page, key) {
  const { violations } = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze();
  axeReport[`${TAG} ${key}`] = violations.map((v) => ({
    id: v.id, impact: v.impact, nodes: v.nodes.length,
    targets: v.nodes.slice(0, 3).map((n) => n.target.join(' ')),
  }));
}

async function shot(page, focus, file) {
  await focus.scrollIntoViewIfNeeded();
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(OUT, file) });
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
  for (const [viewport, size] of Object.entries(VIEWPORTS)) {
    const context = await browser.newContext({ locale: process.env.LOCALE ?? (LANG === 'en' ? 'en-GB' : 'de-DE'), viewport: size });
    const page = await context.newPage();
    const errors = [];
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text().slice(0, 160)));
    page.on('pageerror', (e) => errors.push(e.message.slice(0, 160)));
    await page.goto(`${APP}/`);
    // The sign-in screen follows the browser locale; the signed-in app follows the member's language.
    await page.getByLabel(/^(Email|E-Mail)$/).first().fill(TMW_EMAIL);
    await page.getByLabel(/^(Password|Passwort)$/).first().fill(TMW_PASSWORD);
    await page.getByRole('button', { name: /^(Sign in|Anmelden)$/ }).click();
    await page.getByRole('button', { name: /^(Sign in|Anmelden)$/ }).waitFor({ state: 'detached' });
    await page.getByRole('button', { name: PORTFOLIO, exact: true }).first().click();
    await rowButton(page, FOREIGN).waitFor();

    // 1. Foreign-currency row: converted balance in the accessible name and visible "≈".
    const foreign = await label(page, FOREIGN);
    check(`${viewport} foreign row announces the converted balance`, T.approx.test(foreign), foreign);
    check(`${viewport} foreign row shows "≈" on screen`,
      /≈\s*[-−]\s*EUR\s*108[.,]31/.test(await rowBox(page, FOREIGN).innerText()),
      (await rowBox(page, FOREIGN).innerText()).replace(/\s+/g, ' '));
    // 2. Same-currency row: no conversion.
    const same = await label(page, SAME);
    check(`${viewport} same-currency row has no conversion`, !T.approxWord.test(same), same);
    // 3. Unknown value: one neutral "–", no conversion, no digits in the amount part.
    const unknown = await label(page, UNKNOWN);
    check(`${viewport} unknown row says "${T.unknown}" without conversion`,
      unknown.startsWith(`${UNKNOWN}, ${T.unknown}`) && !T.approxWord.test(unknown), unknown);
    const dashes = rowBox(page, UNKNOWN).getByText('–', { exact: true });
    check(`${viewport} unknown row shows exactly one "–"`, (await dashes.count()) === 1, String(await dashes.count()));
    const dashColor = await dashes.first().evaluate((el) => getComputedStyle(el).color);
    const textColor = await rowButton(page, UNKNOWN).getByText(UNKNOWN.split(' · ')[1]).first()
      .evaluate((el) => getComputedStyle(el).color).catch(() => null);
    check(`${viewport} unknown "–" is not in loss colour`, textColor === null || dashColor === textColor,
      `dash ${dashColor}, name ${textColor}`);
    await shot(page, rowButton(page, FOREIGN), `${TAG}-${viewport}-1-rows.png`);
    await axe(page, `${viewport} Portfolio View`);

    // 4. Hide amounts: no figure in the page text or any aria-label; foreign row says hidden once.
    await page.getByRole('switch', { name: T.hide }).first().click();
    await page.waitForTimeout(500);
    const hiddenLabel = await label(page, FOREIGN);
    check(`${viewport} hidden: foreign row label masked`,
      hiddenLabel.startsWith(`${FOREIGN}, ${T.hidden}`) && !T.approxWord.test(hiddenLabel) && !/108|100/.test(hiddenLabel),
      hiddenLabel);
    const leak = await page.evaluate(() => {
      const labels = [...document.querySelectorAll('[aria-label]')].map((e) => e.getAttribute('aria-label'));
      return [document.body.innerText, ...labels].filter((t) => /108[.,]31|100[.,]00/.test(t)).slice(0, 3);
    });
    check(`${viewport} hidden: no converted or native figure anywhere`, leak.length === 0, leak.join(' | '));
    await shot(page, rowButton(page, FOREIGN), `${TAG}-${viewport}-2-hidden.png`);
    await page.getByRole('switch', { name: T.hide }).first().click();

    check(`${viewport} no console or page error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await context.close();
  }
} catch (error) {
  check('script error', false, error.message.split('\n')[0]);
} finally {
  await browser.close();
  writeFileSync(join(OUT, `axe-results-${TAG}.json`), `${JSON.stringify(axeReport, null, 2)}\n`);
  for (const r of results) console.log(`${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? ` — ${r.detail}` : ''}`);
  for (const [key, v] of Object.entries(axeReport)) {
    console.log(`axe ${key}: ${v.length === 0 ? 'no violations' : ''}`);
    for (const x of v) console.log(`  ${x.impact} ${x.id} (${x.nodes}) ${x.targets.join(' ; ')}`);
  }
}
