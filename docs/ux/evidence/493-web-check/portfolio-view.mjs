// Web check for #493 (S2 Portfolio View), synthetic member from seed.mjs on a local stack.
// Usage: TMW_EMAIL=... TMW_PASSWORD=... node portfolio-view.mjs <outDir>
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';

const out = process.argv[2];
const { TMW_EMAIL: EMAIL, TMW_PASSWORD: PASSWORD } = process.env;
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const log = (...a) => console.log(...a);

async function axe(page) {
  const r = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  return r.violations.map(
    (v) =>
      `${v.id}(${v.impact}) x${v.nodes.length}: ${v.nodes
        .slice(0, 2)
        .map((n) => n.target.join(' '))
        .join(' | ')}`,
  );
}

const hScroll = (page) =>
  page.evaluate(
    () => document.scrollingElement.scrollWidth > document.scrollingElement.clientWidth + 1,
  );

/** RN-web scroll views are inner overflow boxes: unroll them for a full-page screenshot. */
async function shot(page, name) {
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('div')) {
      const s = getComputedStyle(el);
      if (/(auto|scroll)/.test(s.overflowY) && el.scrollHeight > el.clientHeight) {
        el.style.overflow = 'visible';
        el.style.height = 'auto';
        el.style.maxHeight = 'none';
        el.style.flex = 'none';
      }
    }
  });
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${out}/${name}.png`, fullPage: true });
}

async function open(viewport, colorScheme) {
  const ctx = await browser.newContext({ locale: 'en-US', viewport, deviceScaleFactor: 1, colorScheme });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push('console: ' + m.text().slice(0, 160)));
  await page.goto('http://localhost:8081/', { waitUntil: 'networkidle' });
  await page.getByLabel(/^(Email|E-Mail)$/).fill(EMAIL);
  await page.getByLabel(/^(Password|Passwort)$/).fill(PASSWORD);
  await page.getByRole('button', { name: /^(Sign in|Anmelden)$/ }).last().click();
  const skip = page.getByRole('button', { name: 'Skip for now' }).first();
  await skip.click({ timeout: 8000 }).catch(() => undefined);
  await page.getByRole('tab', { name: 'Bank Example' }).waitFor({ timeout: 20000 });
  return { ctx, page, errors };
}

async function portfolio(page, name) {
  await page.getByRole('tab', { name }).click();
  await page.getByRole('button', { name: new RegExp(`^${name} total,`) }).waitFor({ timeout: 15000 });
  await page.waitForTimeout(800);
}

// 1. Phone, light: Bank Example (cash, depot with positions, card) and Home (negative total).
{
  const { ctx, page, errors } = await open({ width: 390, height: 844 }, 'light');
  await portfolio(page, 'Bank Example');
  log('phone tabs:', await page.getByRole('tab').allInnerTexts());
  log('phone selected tab:', await page.locator('[role=tab][aria-selected=true]').allInnerTexts());
  log('phone tables (list expected on phone):', await page.locator('table').count());
  log('phone hScroll', await hScroll(page), 'axe', await axe(page));
  await shot(page, 'en-light-phone-bank');
  await page.getByRole('button', { name: 'Show all 7 positions' }).waitFor();
  log('phone show-all present: yes');

  await portfolio(page, 'Home');
  log('home total label:', await page.getByRole('button', { name: /^Home total,/ }).getAttribute('aria-label'));
  log('home hScroll', await hScroll(page), 'axe', await axe(page));
  await shot(page, 'en-light-phone-home-negative');

  await page.getByRole('button', { name: /^Home total,/ }).click();
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${out}/en-light-phone-explanation.png` });
  await page.getByRole('button', { name: 'Close' }).first().click();

  // "Hide amounts" from the shell, then back on the bank portfolio.
  await page.getByRole('button', { name: /Hide amounts/ }).first().click().catch(async () => {
    await page.getByRole('switch', { name: /Hide amounts/ }).first().click();
  });
  await portfolio(page, 'Bank Example');
  const leak = await page.evaluate(() => {
    const labels = [...document.querySelectorAll('[aria-label]')].map((e) => e.getAttribute('aria-label'));
    const text = document.body.innerText + '\n' + labels.join('\n');
    return text.match(/\d[\d']*\.\d{2}\b|\d+ units|\d+(\.\d)?%/g) ?? [];
  });
  log('hidden: figures still visible:', JSON.stringify(leak));
  await shot(page, 'en-light-phone-hidden');
  log('phone errors:', JSON.stringify(errors));
  await ctx.close();
}

// 2. Desktop, light and dark: two columns, positions as a semantic table.
for (const scheme of ['light', 'dark']) {
  const { ctx, page, errors } = await open({ width: 1280, height: 900 }, scheme);
  await portfolio(page, 'Bank Example');
  const table = await page.evaluate(() => {
    const t = document.querySelector('table');
    if (!t) return null;
    return {
      caption: t.querySelector('caption')?.textContent,
      headers: [...t.querySelectorAll('th')].map((th) => `${th.textContent}[scope=${th.getAttribute('scope')}]`),
      rows: t.querySelectorAll('tbody tr').length,
      links: [...t.querySelectorAll('[role=link]')].length,
    };
  });
  log(`${scheme} desktop table:`, JSON.stringify(table));
  log(`${scheme} desktop hScroll`, await hScroll(page), 'axe', await axe(page));
  await shot(page, `en-${scheme}-desktop-bank`);
  log(`${scheme} errors:`, JSON.stringify(errors));
  await ctx.close();
}

await browser.close();
