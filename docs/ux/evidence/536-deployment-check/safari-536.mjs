// #536: sign-out and Back in real Safari via safaridriver (W3C WebDriver, no dependencies).
// Needs `sudo safaridriver --enable` once and `safaridriver -p 4444` running.
// TMW_EMAIL / TMW_PASSWORD from the environment; APP (default http://localhost:8084); OUT=<dir>.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const DRIVER = process.env.DRIVER ?? 'http://127.0.0.1:4444';
const APP = process.env.APP ?? 'http://localhost:8084';
const OUT = process.env.OUT ?? '.';
const EXTERNAL = 'https://example.com/';
const MARKER = 'Demo · ';
const { TMW_EMAIL, TMW_PASSWORD } = process.env;
if (!TMW_EMAIL || !TMW_PASSWORD) throw new Error('Set TMW_EMAIL and TMW_PASSWORD');
const ELEMENT = 'element-6066-11e4-a52e-4f735466cecf';

const results = [];
const check = (name, pass, detail = '') => results.push({ name, pass: Boolean(pass), detail });

async function wd(method, path, body) {
  const res = await fetch(DRIVER + path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`${method} ${path}: ${json.value?.error} ${json.value?.message ?? ''}`.trim());
  return json.value;
}

const created = await wd('POST', '/session', { capabilities: { alwaysMatch: { browserName: 'safari' } } });
const S = `/session/${created.sessionId}`;
const version = created.capabilities.browserVersion;
const run = (script, args = []) => wd('POST', `${S}/execute/sync`, { script, args });
const text = () => run('return document.body ? document.body.innerText : ""');
const url = () => wd('GET', `${S}/url`);

// Finds an element by ARIA role and accessible name (aria-label or text) and returns a reference.
const FIND = `
  const [role, names, prefix] = arguments;
  const wanted = Array.isArray(names) ? names : [names];
  const nodes = [...document.querySelectorAll('[role="' + role + '"]' + (role === 'button' ? ', button' : '') + (role === 'link' ? ', a' : ''))];
  return nodes.find((n) => {
    const label = (n.getAttribute('aria-label') || n.innerText || '').trim();
    return wanted.some((name) => (prefix ? label.startsWith(name) : label === name));
  }) || null;`;

async function waitFor(fn, what, timeout = 20000) {
  const end = Date.now() + timeout;
  for (;;) {
    const value = await fn().catch(() => null);
    if (value) return value;
    if (Date.now() > end) throw new Error(`Timed out waiting for ${what}`);
    await delay(300);
  }
}
const find = (role, name, prefix = false) => waitFor(() => run(FIND, [role, name, prefix]), `${role} "${name}"`);
const click = async (role, name, prefix) => wd('POST', `${S}/element/${(await find(role, name, prefix))[ELEMENT]}/click`, {});
const SIGN_IN = ['Sign in', 'Anmelden'];
const signInShown = async () => Boolean(await run(FIND, ['button', SIGN_IN, false]));

async function type(selector, value) {
  const el = await waitFor(() => wd('POST', `${S}/element`, { using: 'css selector', value: selector }), selector);
  await wd('POST', `${S}/element/${el[ELEMENT]}/clear`, {});
  await wd('POST', `${S}/element/${el[ELEMENT]}/value`, { text: value });
}

async function signIn() {
  await find('button', SIGN_IN);
  await type('input[type="email"], input[autocomplete="email"], input[autocomplete="username"]', TMW_EMAIL);
  await type('input[type="password"]', TMW_PASSWORD);
  await click('button', SIGN_IN);
  await waitFor(async () => (await text()).includes(MARKER), 'workspace data after sign-in');
}

async function openDepot() {
  if (!/\/accounts\/[^/?]+$/.test(new URL(await url()).pathname)) {
    await click('button', 'Demo · Alpine Lantern Investments');
    await click('button', 'Demo · Alpine Lantern Depot', true);
  }
  await waitFor(async () => /\/accounts\/[^/?]+$/.test(new URL(await url()).pathname), 'account URL');
  await waitFor(async () => (await text()).includes('Demo · Alpine Lantern Depot'), 'depot name');
}

async function screenshot(file) {
  const png = await wd('GET', `${S}/screenshot`);
  writeFileSync(join(OUT, file), Buffer.from(png, 'base64'));
}

try {
  await wd('POST', `${S}/window/rect`, { width: 1280, height: 900 }).catch(() => null);
  await wd('POST', `${S}/url`, { url: `${APP}/` });
  await signIn();
  check('sign-in works', true);
  await openDepot();
  await screenshot('safari-1-account.png');

  // Leave the site, then Back. Either a fresh sign-in (no-store) or the same member's own page
  // restored with the session intact is acceptable (PO 2026-11-14); never another state.
  await wd('POST', `${S}/url`, { url: EXTERNAL });
  await waitFor(async () => (await url()).startsWith(EXTERNAL), 'external page');
  await wd('POST', `${S}/back`, {});
  await delay(3000);
  const restored = (await text()).includes('Demo · Alpine Lantern Depot');
  const signIn1 = await signInShown();
  check(
    'Back after leaving the site: sign-in form, or the same member still signed in',
    signIn1 !== restored,
    signIn1 ? 'sign-in form (page loaded afresh)' : 'same session restored',
  );
  await screenshot('safari-2-back-after-leaving.png');

  // Sign out, then Back repeatedly.
  if (signIn1) await signIn();
  await openDepot();
  await click('link', ['Settings', 'Einstellungen']);
  await click('button', ['Sign out', 'Abmelden'], true);
  await delay(800);
  const confirm = await run(`return [...document.querySelectorAll('[role="button"], button')]
    .filter((n) => /sign out|abmelden/i.test(n.getAttribute('aria-label') || n.innerText || '')).length`);
  if (confirm > 1) {
    await wd('POST', `${S}/element/${(await run(`return [...document.querySelectorAll('[role="button"], button')]
      .filter((n) => /sign out|abmelden/i.test(n.getAttribute('aria-label') || n.innerText || '')).pop()`))[ELEMENT]}/click`, {}).catch(() => null);
  }
  await waitFor(signInShown, 'sign-in form after sign-out');
  check('sign-out lands on the sign-in form', new URL(await url()).pathname === '/overview', await url());
  const leaks = [];
  for (let i = 1; i <= 6; i++) {
    await wd('POST', `${S}/back`, {}).catch(() => null);
    await delay(1500);
    if ((await text()).includes(MARKER)) leaks.push(`${i}: ${await url()}`);
    if (i === 2) await screenshot('safari-3-back-after-sign-out.png');
  }
  check('after sign-out, Back ×6 never shows workspace data', leaks.length === 0, leaks.join(' | '));
} catch (error) {
  check('script error', false, error.message.split('\n')[0]);
  await screenshot('safari-error.png').catch(() => null);
} finally {
  await wd('DELETE', S).catch(() => null);
  writeFileSync(join(OUT, 'results-safari.json'), `${JSON.stringify({ browser: 'safari', version, results }, null, 2)}\n`);
  console.log(`safari ${version}`);
  for (const r of results) console.log(`${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? ` — ${r.detail}` : ''}`);
}
