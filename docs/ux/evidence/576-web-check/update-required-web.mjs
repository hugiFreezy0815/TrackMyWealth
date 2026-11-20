// #576 web check: a backend whose minimum app version (9.0.0) is above this build's (1.0.0)
// answers the sign-in with 426, and the web build shows the update screen.
// Usage: node update-required-web.mjs <webUrl> <apiUrl> <outDir>
import { chromium } from 'playwright-core';
import { writeFileSync } from 'node:fs';

const [webUrl, apiUrl, outDir] = process.argv.slice(2);
const results = [];
const log = (line) => {
  results.push(line);
  console.log(line);
};

const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function run({ name, locale, viewport, colorScheme, labels }) {
  const context = await browser.newContext({ locale, viewport, colorScheme });
  const page = await context.newPage();
  const apiCalls = [];
  page.on('request', (request) => {
    if (request.url().startsWith(apiUrl + '/api/')) {
      apiCalls.push({ method: request.method(), url: request.url(), headers: request.headers() });
    }
  });
  const statuses = [];
  page.on('response', (response) => {
    if (response.url().startsWith(apiUrl + '/api/')) {
      statuses.push(`${response.request().method()} ${response.status()} ${response.url()}`);
    }
  });
  await page.goto(webUrl);
  await page.getByLabel(labels.email, { exact: true }).fill('member@example.com');
  await page.getByLabel(labels.password, { exact: true }).fill('correct-horse-battery-staple');
  await page.getByRole('button', { name: labels.signIn, exact: true }).click();
  await page.getByRole('heading', { name: labels.title }).waitFor({ timeout: 15000 });
  const login = apiCalls.find((call) => call.method === 'POST' && call.url.endsWith('/auth/login'));
  log(`[${name}] login request X-App-Version: ${login?.headers['x-app-version']}`);
  log(`[${name}] responses: ${statuses.join(' | ')}`);
  log(`[${name}] heading shown: ${await page.getByRole('heading', { name: labels.title }).isVisible()}`);
  log(`[${name}] body: ${await page.getByText(labels.body).isVisible()}`);
  log(`[${name}] version line: ${await page.getByText(labels.version).isVisible()}`);
  const reload = page.getByRole('button', { name: labels.reload });
  log(`[${name}] reload button: ${await reload.isVisible()}`);
  log(`[${name}] sign-in form hidden: ${!(await page.getByLabel(labels.password, { exact: true }).isVisible().catch(() => false))}`);
  await page.screenshot({ path: `${outDir}/${name}.png`, fullPage: true });
  await reload.click();
  await page.getByLabel(labels.password, { exact: true }).waitFor({ timeout: 15000 });
  log(`[${name}] after reload, sign-in form back: true`);
  await context.close();
}

const en = {
  email: 'Email',
  password: 'Password',
  signIn: 'Sign in',
  title: 'Update required',
  body: 'Reload the page to load the current version.',
  version: 'Installed version: 1.0.0',
  reload: 'Reload page',
};
const de = {
  email: 'E-Mail',
  password: 'Passwort',
  signIn: 'Anmelden',
  title: 'Update erforderlich',
  body: 'Lade die Seite neu, um die aktuelle Version zu laden.',
  version: 'Installierte Version: 1.0.0',
  reload: 'Seite neu laden',
};

await run({ name: 'en-phone-light', locale: 'en-GB', viewport: { width: 390, height: 844 }, colorScheme: 'light', labels: en });
await run({ name: 'en-desktop-dark', locale: 'en-GB', viewport: { width: 1280, height: 800 }, colorScheme: 'dark', labels: en });
await run({ name: 'de-phone-light', locale: 'de-DE', viewport: { width: 390, height: 844 }, colorScheme: 'light', labels: de });

await browser.close();
writeFileSync(`${outDir}/results.txt`, results.join('\n') + '\n');
