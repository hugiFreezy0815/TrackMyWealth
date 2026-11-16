// #536: browser checks against a production-shaped deployment (Compose `web` profile behind an
// HTTPS reverse proxy; web https://localhost, API https://localhost:8443). Chrome with its
// back-forward cache on, and Playwright WebKit as a stand-in for Safari.
// TMW_EMAIL / TMW_PASSWORD from the environment; BROWSER=chrome|webkit; OUT=<dir>.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { chromium, webkit } from 'playwright-core';

const APP = process.env.APP ?? 'https://localhost';
const OUT = process.env.OUT ?? '.';
const BROWSER = process.env.BROWSER ?? 'chrome';
const EXT = 'https://external.example.test';
const MARKER = 'Demo · '; // every seeded portfolio and account name starts with it
const { TMW_EMAIL, TMW_PASSWORD } = process.env;
if (!TMW_EMAIL || !TMW_PASSWORD) throw new Error('Set TMW_EMAIL and TMW_PASSWORD');

const results = [];
const check = (name, pass, detail = '') => results.push({ name, pass: Boolean(pass), detail });
const bodyText = (page) => page.locator('body').innerText().catch(() => '');
const signInVisible = (page) => page.getByRole('button', { name: 'Sign in' }).isVisible().catch(() => false);
const back = (page) => page.goBack({ waitUntil: 'commit' }).catch(() => null);

async function signIn(page) {
  await page.getByRole('button', { name: 'Sign in' }).waitFor();
  await page.getByLabel('Email').first().fill(TMW_EMAIL);
  await page.getByLabel('Password').first().fill(TMW_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('button', { name: 'Sign in' }).waitFor({ state: 'detached', timeout: 20000 });
}

async function openDepot(page) {
  // After sign-in on a deep link the app opens that account directly (#553).
  if (/\/accounts\/[^/?]+$/.test(new URL(page.url()).pathname)) {
    await page.getByText('Demo · Alpine Lantern Depot').first().waitFor();
    return;
  }
  await page.getByRole('button', { name: 'Demo · Alpine Lantern Investments', exact: true }).first().click();
  await page.getByRole('button', { name: /^Demo · Alpine Lantern Depot/ }).click();
  await page.waitForURL(/\/accounts\/[^/?]+$/);
  await page.getByText('Demo · Alpine Lantern Depot').first().waitFor();
}

const launcher = BROWSER === 'webkit' ? webkit : chromium;
const browser = await launcher.launch(
  BROWSER === 'webkit'
    ? { headless: true }
    : { channel: 'chrome', headless: true, ignoreDefaultArgs: ['--disable-back-forward-cache'] },
);
const version = browser.version();
try {
  // The proxy's certificate comes from Caddy's local CA, which this machine does not trust.
  const context = await browser.newContext({ locale: 'en-GB', ignoreHTTPSErrors: true, viewport: { width: 1280, height: 900 } });
  const referers = [];
  await context.route(`${EXT}/**`, (route) => {
    referers.push(route.request().headers()['referer'] ?? null);
    if (new URL(route.request().url()).pathname === '/frame') {
      return route.fulfill({ contentType: 'text/html', body: `<h1>other site</h1><iframe src="${APP}/" width="600" height="400"></iframe>` });
    }
    return route.fulfill({ contentType: 'text/html', body: '<h1>external</h1>' });
  });
  await context.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => {
      try {
        const list = JSON.parse(sessionStorage.getItem('csp') || '[]');
        list.push(`${e.effectiveDirective} ${e.blockedURI || 'inline'}`);
        sessionStorage.setItem('csp', JSON.stringify(list));
      } catch {}
    });
  });
  const page = await context.newPage();
  const csp = new Set();
  const collectCsp = async () =>
    (await page.evaluate(() => JSON.parse(sessionStorage.getItem('csp') || '[]')).catch(() => [])).forEach((v) => csp.add(v));
  const apiCalls = [];
  page.on('request', (r) => r.url().startsWith('https://localhost:8443/') && apiCalls.push(r.headers()['referer'] ?? null));

  // 1. Sign in over HTTPS against the separate API origin.
  const first = await page.goto(`${APP}/`);
  check('HTML over HTTPS carries Cache-Control: no-store', first.headers()['cache-control'] === 'no-store');
  await signIn(page);
  const signedIn = await page.getByText(MARKER).first().waitFor({ timeout: 20000 }).then(() => true, () => false);
  check('sign-in works (CORS + CSP connect-src)', signedIn);
  await openDepot(page);
  await page.screenshot({ path: join(OUT, `${BROWSER}-1-account.png`) });
  await collectCsp();

  // 2. External link from the account URL: no Referer; then Back after leaving the site.
  await page.evaluate((u) => {
    const a = document.createElement('a');
    a.href = u;
    a.id = 'ext';
    a.textContent = 'external';
    document.body.appendChild(a);
  }, `${EXT}/landing`);
  await Promise.all([page.waitForURL(`${EXT}/landing`), page.click('#ext')]);
  check('no Referer to another site from an account URL', referers.at(-1) === null, String(referers.at(-1)));
  check('no Referer on API requests', apiCalls.length > 0 && apiCalls.every((r) => r === null), `${apiCalls.length} calls`);
  await back(page);
  await page.waitForTimeout(3000);
  const afterLeave = await bodyText(page);
  check(
    'Back after leaving the site: sign-in form, no workspace data (PO 2026-11-14)',
    (await signInVisible(page)) && !afterLeave.includes(MARKER),
  );
  await page.screenshot({ path: join(OUT, `${BROWSER}-2-back-after-leaving.png`) });

  // 3. Sign in again, open the account, sign out, then press Back repeatedly.
  await signIn(page);
  await openDepot(page);
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: /sign out/i }).first().click();
  await page.waitForTimeout(800);
  const confirm = page.getByRole('button', { name: /sign out/i });
  if ((await confirm.count()) > 1) await confirm.last().click().catch(() => {});
  await page.getByRole('button', { name: 'Sign in' }).waitFor({ timeout: 20000 });
  check('sign-out lands on the sign-in form', new URL(page.url()).pathname === '/overview', page.url());
  const leaks = [];
  for (let i = 1; i <= 6; i++) {
    await back(page);
    await page.waitForTimeout(1500);
    const text = await bodyText(page);
    if (text.includes(MARKER)) leaks.push(`${i}: ${page.url()}`);
    if (i === 2) await page.screenshot({ path: join(OUT, `${BROWSER}-3-back-after-sign-out.png`) });
  }
  check('after sign-out, Back ×6 never shows workspace data', leaks.length === 0, leaks.join(' | '));
  await collectCsp();
  check('no CSP violation', csp.size === 0, [...csp].join(', '));

  // 4. Another origin cannot frame the app.
  const framer = await context.newPage();
  await framer.goto(`${EXT}/frame`);
  await framer.waitForTimeout(2500);
  const frame = framer.frames().find((f) => f !== framer.mainFrame());
  const framed = frame ? await frame.locator('body').innerText().catch(() => '') : '';
  check('framing from another origin is refused', !/Sign in|Demo/.test(framed), frame?.url() ?? 'no frame');
  await context.close();
} catch (error) {
  check('script error', false, error.message.split('\n')[0]);
} finally {
  await browser.close();
  writeFileSync(join(OUT, `results-${BROWSER}.json`), `${JSON.stringify({ browser: BROWSER, version, results }, null, 2)}\n`);
  console.log(`${BROWSER} ${version}`);
  for (const r of results) console.log(`${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? ` — ${r.detail}` : ''}`);
}
