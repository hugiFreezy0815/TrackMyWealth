// #649 web check: the W14 Update required screen after a 426, in EN/DE, light/dark,
// phone/desktop and at real 200 % Chrome zoom, plus an axe audit of each state.
// The API is mocked: every request answers 426 APP_UPDATE_REQUIRED (no backend needed).
// Usage: node update-required-649.mjs <webUrl> <apiOrigin> <i18nDir> <outDir>
import { chromium } from 'playwright-core';
import AxeBuilder from '@axe-core/playwright';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [webUrl, apiOrigin, i18nDir, outDir] = process.argv.slice(2);
mkdirSync(outDir, { recursive: true });
const catalogue = (lang) => JSON.parse(readFileSync(join(i18nDir, `${lang}.json`), 'utf8'));
const results = [];
const log = (line) => {
  results.push(line);
  console.log(line);
};

async function mockApi(context) {
  await context.route(`${apiOrigin}/**`, (route) => {
    const cors = {
      'access-control-allow-origin': '*',
      'access-control-allow-headers': '*',
      'access-control-allow-methods': '*',
    };
    if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
    return route.fulfill({
      status: 426,
      headers: { ...cors, 'content-type': 'application/problem+json' },
      body: JSON.stringify({ title: 'Update required', status: 426, code: 'APP_UPDATE_REQUIRED' }),
    });
  });
}

async function check(page, name, t, { cdpShot = false } = {}) {
  await page.goto(webUrl);
  await page.getByLabel(t['auth.email'], { exact: true }).fill('member@example.com');
  await page.getByLabel(t['auth.password'], { exact: true }).fill('correct-horse-battery-staple');
  await page.getByRole('button', { name: t['auth.signIn'], exact: true }).click();
  const heading = page.getByRole('heading', { name: t['updateRequired.title'] });
  await heading.waitFor({ timeout: 20000 });
  await page.waitForTimeout(1500);

  const button = page.getByRole('button', { name: t['updateRequired.reload'] });
  const box = await button.boundingBox();
  const metrics = await page.evaluate(() => ({
    innerWidth: window.innerWidth,
    innerHeight: window.innerHeight,
    dpr: window.devicePixelRatio,
    hScroll: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    alerts: document.querySelectorAll('[role=alert]').length,
  }));
  const version = t['updateRequired.version'].replace('{version}', '1.0.0');
  log(`[${name}] viewport ${metrics.innerWidth}x${metrics.innerHeight} dpr ${metrics.dpr}`);
  log(`[${name}] heading: ${await heading.isVisible()}`);
  log(`[${name}] web body: ${await page.getByText(t['updateRequired.body.web']).isVisible()}`);
  log(`[${name}] version caption: ${await page.getByText(version).isVisible()}`);
  log(`[${name}] icon: ${(await page.locator('img').count()) > 0}`);
  log(`[${name}] reload button ${Math.round(box.width)}x${Math.round(box.height)} px, inside viewport: ${box.y + box.height <= metrics.innerHeight && box.x >= 0 && box.x + box.width <= metrics.innerWidth}`);
  log(`[${name}] horizontal scroll: ${metrics.hScroll}`);
  log(`[${name}] role=alert regions: ${metrics.alerts}`);
  log(`[${name}] sign-in form hidden: ${!(await page.getByLabel(t['auth.password'], { exact: true }).isVisible().catch(() => false))}`);

  const axe = await new AxeBuilder({ page }).analyze();
  log(`[${name}] axe violations: ${axe.violations.length}${axe.violations.map((v) => ` ${v.id}(${v.nodes.length})`).join('')}`);

  if (cdpShot) {
    const cdp = await page.context().newCDPSession(page);
    const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(`${outDir}/${name}.png`, Buffer.from(data, 'base64'));
  } else {
    await page.screenshot({ path: `${outDir}/${name}.png` });
  }

  await page.keyboard.press('Tab');
  const focused = await page.evaluate(() => document.activeElement?.textContent ?? '');
  log(`[${name}] first Tab focuses: "${focused}"`);
  await button.click();
  await page.getByLabel(t['auth.password'], { exact: true }).waitFor({ timeout: 20000 });
  log(`[${name}] reload brings the sign-in back: true`);
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });
for (const lang of ['en', 'de']) {
  const t = catalogue(lang);
  for (const colorScheme of ['light', 'dark']) {
    for (const [form, viewport] of [
      ['phone', { width: 390, height: 844 }],
      ['desktop', { width: 1280, height: 800 }],
    ]) {
      const context = await browser.newContext({ locale: lang === 'en' ? 'en-GB' : 'de-DE', viewport, colorScheme });
      await mockApi(context);
      await check(await context.newPage(), `${lang}-${form}-${colorScheme}`, t);
      await context.close();
    }
  }
}
await browser.close();

// Real Chrome zoom at 200 %: zoom level x with 1.2^x = 2.
for (const [lang, size] of [
  ['en', '1280,800'],
  ['de', '780,844'],
]) {
  const t = catalogue(lang);
  const profile = mkdtempSync(join(tmpdir(), 'zoom-'));
  mkdirSync(join(profile, 'Default'));
  writeFileSync(
    join(profile, 'Default', 'Preferences'),
    JSON.stringify({ partition: { default_zoom_level: { x: Math.log(2) / Math.log(1.2) } } }),
  );
  const context = await chromium.launchPersistentContext(profile, {
    channel: 'chrome',
    headless: true,
    viewport: null,
    locale: lang === 'en' ? 'en-GB' : 'de-DE',
    args: [`--window-size=${size}`],
  });
  await mockApi(context);
  await check(context.pages()[0] ?? (await context.newPage()), `${lang}-zoom200-${size.replace(',', 'x')}`, t, { cdpShot: true });
  await context.close();
}

writeFileSync(`${outDir}/results.txt`, results.join('\n') + '\n');
