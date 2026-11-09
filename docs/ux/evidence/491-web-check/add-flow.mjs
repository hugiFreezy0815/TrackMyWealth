// Web check for #491 (S5 Add portfolio, S6 Add account), synthetic member on a local stack.
// Usage: TMW_EMAIL=... TMW_PASSWORD=... node add-flow.mjs <outDir>
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';

const out = process.argv[2];
const EMAIL = process.env.TMW_EMAIL;
const PASSWORD = process.env.TMW_PASSWORD;
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const log = (...a) => console.log(...a);

async function signIn(page) {
  await page.goto('http://localhost:8081/', { waitUntil: 'networkidle' });
  await page.getByLabel(/^(Email|E-Mail)$/).fill(EMAIL);
  await page.getByLabel(/^(Password|Passwort)$/).fill(PASSWORD);
  await page.getByRole('button', { name: /^(Sign in|Anmelden)$/ }).last().click();
}

async function axe(page) {
  const r = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  return r.violations.map(
    (v) => `${v.id}(${v.impact}) x${v.nodes.length}: ${v.nodes.slice(0, 2).map((n) => n.target.join(' ')).join(' | ')}`,
  );
}

const hScroll = (page) =>
  page.evaluate(() => document.scrollingElement.scrollWidth > document.scrollingElement.clientWidth + 1);

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
  await page.waitForTimeout(200);
  await page.screenshot({ path: `${out}/${name}.png`, fullPage: true });
}

function watch(page) {
  const errors = [];
  const writes = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push('console: ' + m.text().slice(0, 160)));
  page.on('request', (r) => {
    if (['POST', 'PUT'].includes(r.method()) && r.url().includes('/api/v1/') && !r.url().includes('/auth/'))
      writes.push({ method: r.method(), path: new URL(r.url()).pathname, ifMatch: r.headers()['if-match'] ?? null, body: r.postData() });
  });
  return { errors, writes };
}

const ctx = await browser.newContext({ locale: 'en-US', viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, colorScheme: 'light' });
const page = await ctx.newPage();
const { errors, writes } = watch(page);

// --- 1. First run: DKB, current account, IBAN, opening balance EUR 1'000 today --------------
await signIn(page);
await page.getByRole('button', { name: 'Skip for now' }).first().click({ timeout: 20000 });
await page.getByRole('button', { name: /Add your first portfolio/ }).click({ timeout: 20000 });
const started = Date.now();
await page.getByText('Step 1 of 2').waitFor();
log('S5 headings:', await page.getByRole('heading').allInnerTexts());
await page.getByLabel('Search banks, brokers and 3a providers').fill('DKB');
await page.getByRole('radio', { name: 'DKB', exact: true }).click({ timeout: 10000 });
await page.waitForTimeout(400);
log('S5 focused after pick:', await page.evaluate(() => document.activeElement?.textContent?.slice(0, 40)));
log('S5 hScroll', await hScroll(page), 'axe', await axe(page));
await shot(page, 's5-dkb-selected-phone');
await page.getByRole('button', { name: 'Create portfolio' }).click();
await page.getByText('Add an account to DKB').waitFor({ timeout: 10000 });
log('S6 hScroll', await hScroll(page), 'axe', await axe(page));
await shot(page, 's6-step1-phone');
await page.getByLabel('IBAN').fill('de89 3704 0044 0532 0130 00');
log('IBAN field shows', await page.getByLabel('IBAN').inputValue());
await page.getByRole('button', { name: 'Add account' }).click();
await page.getByText('Where does the value start?').waitFor({ timeout: 10000 });
await page.getByLabel('Opening balance').fill('1000');
await shot(page, 's6-step2-opening-balance-phone');
await page.getByRole('button', { name: 'Save starting value' }).click();
await page.getByTestId('toast').waitFor({ timeout: 10000 });
log('toast:', await page.getByTestId('toast').innerText());
log('toast box (viewport 844 high):', JSON.stringify(await page.getByTestId('toast').boundingBox()));
await page.getByText(/1.000\.00/).first().waitFor({ timeout: 10000 });
const seconds = Math.round((Date.now() - started) / 1000);
const body = await page.evaluate(() => document.body.innerText);
log('first run took (automated) s:', seconds, '| EUR 1000 shown:', /EUR\s*1.000\.00/.test(body));
await shot(page, 'overview-after-first-account-phone');

// --- 2. Own portfolio in Sweden (SEK) with a credit card, number masked on the device --------
await page.getByRole('tab', { name: '+ Add portfolio' }).click();
await page.getByRole('radio', { name: /Create your own/ }).click({ timeout: 10000 });
await page.getByLabel('Portfolio name').fill('Nordic card');
await page.getByRole('button', { name: /^Country:/ }).click();
await page.getByLabel('Search', { exact: true }).fill('swed');
await page.getByRole('radio', { name: 'Sweden' }).click();
// The closed sheet must leave the DOM (RN-web keeps it only for the slide-out animation).
await page.waitForFunction(() => document.querySelectorAll('input[aria-label="Search"]').length === 0, null, { timeout: 3000 });
log('country sheet closed and unmounted');
await page.getByRole('button', { name: /^Portfolio currency:/ }).click();
await page.getByLabel('Search', { exact: true }).fill('kron');
await shot(page, 's5-currency-search-phone');
await page.getByRole('radio', { name: 'SEK · Swedish Krona' }).click();
await page.getByRole('button', { name: 'Create portfolio' }).click();
await page.getByText('Add an account to Nordic card').waitFor({ timeout: 10000 });
await page.getByRole('radio', { name: 'Credit card' }).click();
await page.getByLabel('Card number').fill('4242 4242 4242 4242');
await page.getByLabel('Account name').click(); // blur
log('card field shows', await page.getByLabel('Card number').inputValue());
await shot(page, 's6-credit-card-masked-phone');
await page.getByRole('button', { name: 'Add account' }).click();
await page.getByText('Where does the value start?').waitFor({ timeout: 10000 });
await page.getByRole('button', { name: 'Skip for now' }).click();
await page.getByTestId('toast').waitFor({ timeout: 10000 });

// --- 3. An invalid IBAN: the account stays, the field says why, Skip goes on -----------------
await page.getByRole('tab', { name: '+ Add portfolio' }).click();
await page.getByRole('radio', { name: /Create your own/ }).click({ timeout: 10000 });
await page.getByLabel('Portfolio name').fill('Test bank');
await page.getByRole('button', { name: 'Create portfolio' }).click();
await page.getByText('Add an account to Test bank').waitFor({ timeout: 10000 });
await page.getByLabel('IBAN').fill('DE00 1234 5678');
await page.getByRole('button', { name: 'Add account' }).click();
await page.getByText(/The account was created/).waitFor({ timeout: 10000 });
log('identifier error:', await page.getByText(/valid IBAN|another account/).first().innerText());
log('identifier axe', await axe(page));
await shot(page, 's6-invalid-iban-phone');
await page.getByRole('button', { name: 'Skip for now' }).click();
await page.getByText('Where does the value start?').waitFor();
await page.getByRole('button', { name: 'Skip for now' }).click();
await page.getByTestId('toast').waitFor({ timeout: 10000 });

for (const w of writes) log('write', w.method, w.path, 'If-Match', w.ifMatch, w.body);
log('full PAN sent anywhere:', writes.some((w) => (w.body ?? '').includes('4242424242424242')));
log('errors', errors);
await ctx.close();

// --- 4. Dark desktop: S5 with an entry picked -------------------------------------------------
const dark = await browser.newContext({ locale: 'en-US', viewport: { width: 1280, height: 900 }, colorScheme: 'dark' });
const dpage = await dark.newPage();
const dwatch = watch(dpage);
await signIn(dpage);
await dpage.getByRole('tab', { name: '+ Add portfolio' }).click({ timeout: 20000 });
await dpage.getByRole('radio', { name: 'DKB', exact: true }).click({ timeout: 10000 });
await dpage.waitForTimeout(300);
log('desktop axe', await axe(dpage), 'hScroll', await hScroll(dpage));
await shot(dpage, 's5-dkb-selected-dark-desktop');
log('desktop errors', dwatch.errors);
await browser.close();
