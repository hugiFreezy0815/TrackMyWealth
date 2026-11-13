// PR #558 web check: sign-in Return-key flow, double-submit guard, MFA step reachability, axe.
// The API is mocked with page.route, so no backend stack is needed.
import { chromium } from 'playwright-core';
import AxeBuilder from '@axe-core/playwright';

const APP = process.env.APP ?? 'http://localhost:8083';
const API = 'http://localhost:8099';
const OUT = process.env.OUT ?? '.';
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
};
const cors = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': '*',
};

const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function newPage(viewport) {
  const context = await browser.newContext({ locale: 'en-GB', viewport });
  const page = await context.newPage();
  const calls = [];
  await page.route(`${API}/**`, async (route) => {
    const req = route.request();
    if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
    const path = new URL(req.url()).pathname;
    calls.push({ method: req.method(), path, body: req.postData() });
    if (path === '/api/v1/auth/login') {
      await new Promise((r) => setTimeout(r, 1500)); // keep the form busy
      return route.fulfill({
        status: 200,
        headers: { ...cors, 'content-type': 'application/json' },
        body: JSON.stringify({ mfaRequired: true, mfaChallengeToken: 'challenge-1' }),
      });
    }
    return route.fulfill({
      status: 401,
      headers: { ...cors, 'content-type': 'application/problem+json' },
      body: JSON.stringify({ type: 'about:blank', title: 'Unauthorized', status: 401, code: 'UNAUTHORIZED' }),
    });
  });
  await page.goto(APP);
  await page.getByLabel('Email').waitFor({ timeout: 60000 });
  return { context, page, calls };
}

async function axe(page, label) {
  const { violations } = await new AxeBuilder({ page }).analyze();
  check(`axe: no violations on ${label}`, violations.length === 0,
    violations.map((v) => `${v.id}(${v.impact})`).join(', '));
}

async function hScroll(page) {
  return page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
}

// 1) Desktop-ish phone width: Return-key flow + double submit + MFA step
{
  const { context, page, calls } = await newPage({ width: 390, height: 844 });
  await page.screenshot({ path: `${OUT}/web-signin-390.png` });
  await axe(page, 'sign-in');

  await page.getByLabel('Server').press('Enter');
  const afterServer = await page.evaluate(() => document.activeElement?.getAttribute('aria-label'));
  check('Return in Server moves focus to Email', afterServer === 'Email', `focused=${afterServer}`);

  await page.getByLabel('Email').fill('demo@example.com');
  await page.getByLabel('Email').press('Enter');
  const focused = await page.evaluate(() => document.activeElement?.getAttribute('aria-label'));
  check('Return in Email moves focus to Password', focused === 'Password', `focused=${focused}`);
  check('Return in Email does not submit', calls.filter((c) => c.path.endsWith('/login')).length === 0);

  await page.getByLabel('Password').fill('secret-password');
  await page.getByLabel('Password').press('Enter');
  await page.waitForTimeout(200);
  await page.getByLabel('Password').press('Enter'); // second Return while busy
  await page.getByRole('button', { name: 'Sign in' }).click({ force: true }).catch(() => {});
  const busy = await page.getByRole('button', { name: 'Sign in' }).getAttribute('aria-busy');
  check('Sign-in button is busy during the request', busy === 'true', `aria-busy=${busy}`);

  await page.getByLabel('Authenticator code').waitFor({ timeout: 10000 });
  const logins = calls.filter((c) => c.path.endsWith('/login'));
  check('Return in Password submits exactly once (busy guard)', logins.length === 1, `login calls=${logins.length}`);
  check('Login body carries the typed credentials',
    logins[0] && JSON.parse(logins[0].body).email === 'demo@example.com');

  await page.screenshot({ path: `${OUT}/web-mfa-390.png` });
  await axe(page, 'MFA step');
  await page.getByLabel('Authenticator code').fill('123456');
  await page.getByLabel('Authenticator code').press('Enter');
  await page.getByRole('alert').waitFor({ timeout: 5000 });
  const verifies = calls.filter((c) => c.path.endsWith('/mfa/verify'));
  check('Enter in Authenticator code submits once', verifies.length === 1, `verify calls=${verifies.length}`);
  const verify = page.getByRole('button', { name: 'Verify code' });
  const startOver = page.getByRole('button', { name: 'Start over' });
  check('MFA: Verify code visible', await verify.isVisible());
  check('MFA: Start over visible', await startOver.isVisible());
  await startOver.click();
  await page.getByLabel('Email').waitFor({ timeout: 5000 });
  check('MFA: Start over returns to sign-in', true);
  await context.close();
}

// 2) Short viewport (keyboard-open stand-in) and 200 % zoom stand-in: everything reachable by scrolling
for (const [w, h, tag] of [[375, 300, '375x300 (keyboard open)'], [188, 400, '188 wide (≈375 pt at 200 %)']]) {
  const { context, page } = await newPage({ width: w, height: h });
  await page.waitForTimeout(300);
  check(`${tag}: no horizontal page scroll`, !(await hScroll(page)));
  for (const name of ['Server', 'Email', 'Password']) {
    const f = page.getByLabel(name, { exact: true });
    await f.scrollIntoViewIfNeeded();
    check(`${tag}: ${name} reachable`, await f.isVisible());
  }
  const btn = page.getByRole('button', { name: 'Sign in' });
  await page.getByLabel('Email').focus();
  await btn.scrollIntoViewIfNeeded();
  const box = await btn.boundingBox();
  check(`${tag}: Sign in reachable by scrolling with a field focused`,
    !!box && box.y >= 0 && box.y + box.height <= h, JSON.stringify(box));
  await page.screenshot({ path: `${OUT}/web-signin-${w}x${h}.png` });
  await page.getByLabel('Password').fill('x');
  await page.getByLabel('Password').press('Enter');
  await page.getByLabel('Authenticator code').waitFor({ timeout: 10000 });
  await page.getByRole('button', { name: 'Start over' }).scrollIntoViewIfNeeded();
  await page.getByRole('button', { name: 'Start over' }).click();
  await page.getByLabel('Server').waitFor();
  await page.waitForTimeout(300);
  const brandTop = await page.getByText('TrackMyWealth').first().boundingBox();
  check(`${tag}: after Start over the card starts at the top`, !!brandTop && brandTop.y >= 0, JSON.stringify(brandTop));
  await context.close();
}

await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
