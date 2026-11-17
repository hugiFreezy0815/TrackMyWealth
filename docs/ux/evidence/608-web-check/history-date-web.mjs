// #608 web check (PR #609): the import line of the S4 change history shows a localised date in
// the device's time zone. Synthetic demo data only.
// Usage: node history-date-web.mjs <web url> <api url> <creds.json> <de-member.json> <out dir>
import { chromium } from 'playwright-core';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';

const [web, api, credsPath, dePath, out] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const { partner } = JSON.parse(readFileSync(credsPath, 'utf8')).users;
const german = JSON.parse(readFileSync(dePath, 'utf8'));
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` - ${detail}` : ''}`);
};

const login = await (
  await fetch(`${api}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: partner.email, password: partner.password }),
  })
).json();
const get = async (path) =>
  (await fetch(`${api}/api/v1${path}`, { headers: { Authorization: `Bearer ${login.tokens.accessToken}` } })).json();
const account = (await get('/accounts')).find((a) => a.name === 'Demo · Paper Moon Current');
const rows = await get(`/accounts/${account.id}/transactions?size=100`);
const row = (rows.content ?? rows).find((t) => t.externalId?.endsWith(':REF-597-1'));
const uploadedAt = row.importBatch.uploadedAt;
const target = `/accounts/${account.id}/transactions/${row.id}`;

// Expected texts, computed independently of the app: the calendar day of uploadedAt in a zone.
const day = (zone, locale) => {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat(locale, {
      timeZone: zone, day: locale === 'de' ? '2-digit' : 'numeric',
      month: locale === 'de' ? '2-digit' : 'short', year: 'numeric',
    }).formatToParts(new Date(uploadedAt)).map((p) => [p.type, p.value]),
  );
  return locale === 'de' ? `${parts.day}.${parts.month}.${parts.year}` : `${parts.day} ${parts.month} ${parts.year}`;
};

const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function run({ user, locale, timezoneId, viewport, colorScheme = 'light', expected, file, axe }) {
  const context = await browser.newContext({ viewport, colorScheme, locale, timezoneId });
  const page = await context.newPage();
  const errors = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.goto(web);
  await page.getByLabel(/^(Email|E-Mail)$/).fill(user.email);
  await page.getByLabel(/^(Password|Passwort)$/).fill(user.password);
  await page.getByRole('button', { name: /^(Sign in|Anmelden)$/ }).click();
  await page.getByLabel(/^(Password|Passwort)$/).waitFor({ state: 'detached', timeout: 30000 });
  await page.waitForTimeout(1000);
  await page.evaluate((path) => {
    history.pushState({}, '', path);
    dispatchEvent(new PopStateEvent('popstate'));
  }, target);
  const line = page.getByText(expected, { exact: true });
  await line.waitFor({ timeout: 30000 }).catch(() => {});
  const found = (await line.count()) === 1;
  const body = await page.locator('body').innerText();
  check(`${locale} ${timezoneId}: "${expected}"`, found,
    found ? '' : body.split('\n').find((l) => /CAMT/.test(l) && /(Imported|importiert)/.test(l)));
  check(`${locale} ${timezoneId}: no ISO date in the page`, !/\d{4}-\d{2}-\d{2}/.test(body));
  if (found) {
    await line.scrollIntoViewIfNeeded();
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${out}/${file}` });
  }
  if (axe) {
    const result = await new AxeBuilder({ page }).analyze();
    const serious = result.violations.filter((v) => ['serious', 'critical'].includes(v.impact));
    check(`${locale}: axe no serious/critical violations`, serious.length === 0,
      result.violations.map((v) => `${v.id}(${v.impact})`).join(', '));
  }
  check(`${locale} ${timezoneId}: no console errors`, errors.length === 0, errors.join(' | ').slice(0, 200));
  await context.close();
}

const phone = { width: 390, height: 844 };
await run({
  user: partner, locale: 'en-GB', timezoneId: 'Europe/Zurich', viewport: phone, axe: true,
  expected: `Imported from CAMT statement on ${day('Europe/Zurich', 'en')}`, file: '01-history-en-phone.png',
});
await run({
  user: german, locale: 'de-CH', timezoneId: 'Europe/Zurich', viewport: { width: 1280, height: 900 },
  colorScheme: 'dark', axe: true,
  expected: `Aus CAMT-Auszug vom ${day('Europe/Zurich', 'de')} importiert`, file: '02-history-de-desktop-dark.png',
});
await run({
  user: partner, locale: 'en-GB', timezoneId: 'Pacific/Kiritimati', viewport: phone,
  expected: `Imported from CAMT statement on ${day('Pacific/Kiritimati', 'en')}`, file: '03-history-en-utc14.png',
});
console.log(`uploadedAt=${uploadedAt}; Zurich=${day('Europe/Zurich', 'en')}; UTC+14=${day('Pacific/Kiritimati', 'en')}`);

await browser.close();
writeFileSync(`${out}/results.json`, JSON.stringify(results, null, 2));
process.exit(results.every((r) => r.ok) ? 0 : 1);
