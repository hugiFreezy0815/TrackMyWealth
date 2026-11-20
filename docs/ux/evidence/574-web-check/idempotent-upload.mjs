// Web check for #574 / PR #630: the statement upload sends an Idempotency-Key, the browser's CORS
// preflight allows it, and a retry after a lost response replays the first upload (one batch).
// Usage: TMW_EMAIL=... TMW_PASSWORD=... node idempotent-upload.mjs <fixture.csv> <outDir>
import { chromium } from 'playwright-core';
import { mkdirSync, writeFileSync } from 'node:fs';

const [fixture, out] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const API = 'http://localhost:8093';
const APP = 'http://localhost:8094';
const EMAIL = process.env.TMW_EMAIL;
const PASSWORD = process.env.TMW_PASSWORD;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const results = { checks: {} };
const check = (name, ok, detail) => {
  results.checks[name] = { ok: Boolean(ok), detail };
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail === undefined ? '' : ' :: ' + JSON.stringify(detail)}`);
};

async function apiToken() {
  const res = await fetch(API + '/api/v1/auth/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: EMAIL, password: PASSWORD }),
  });
  return (await res.json()).tokens.accessToken;
}
async function api(path, token) {
  const res = await fetch(API + path, { headers: { authorization: 'Bearer ' + token } });
  return res.json();
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ locale: 'en-GB', viewport: { width: 1280, height: 900 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => m.type() === 'error' && errors.push('console: ' + m.text().slice(0, 200)));

// Every upload the page sends, with its key; and the preflights Chrome sends for it (CDP only).
const uploads = [];
page.on('request', (req) => {
  if (req.method() === 'POST' && /\/api\/v1\/accounts\/[^/]+\/imports$/.test(req.url())) {
    uploads.push({ uuid: req.headers()['idempotency-key'] ?? null, url: req.url() });
  }
});
const preflights = [];
const cdp = await context.newCDPSession(page);
await cdp.send('Network.enable');
cdp.on('Network.requestWillBeSent', (e) => {
  if (e.request.method === 'OPTIONS' && /\/imports$/.test(e.request.url)) {
    preflights.push({ id: e.requestId, headers: e.request.headers });
  }
});
cdp.on('Network.responseReceived', (e) => {
  const p = preflights.find((x) => x.id === e.requestId);
  if (p) {
    p.status = e.response.status;
    p.allowHeaders = e.response.headers['access-control-allow-headers'] ?? e.response.headers['Access-Control-Allow-Headers'];
  }
});

async function signIn() {
  await page.goto(APP + '/', { waitUntil: 'networkidle' });
  await page.getByLabel(/^Email$/).fill(EMAIL);
  await page.getByLabel(/^Password$/).fill(PASSWORD);
  await page.getByRole('button', { name: /^Sign in$/ }).last().click();
  await page.getByText(/Good (morning|afternoon|evening)/).first().waitFor({ timeout: 30000 });
}

const reviewReady = () =>
  page.getByRole('button', { name: /^Import \d+ transactions?$/ }).waitFor({ timeout: 30000 });

async function startUpload() {
  await page.getByRole('button', { name: 'Import statement' }).first().click();
  await page.getByRole('radio', { name: /Paper Moon Current/ }).click();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'Choose file' }).click();
  await (await chooser).setFiles(fixture);
}

async function discardAndLeave() {
  await page.getByRole('button', { name: 'Cancel' }).click();
  await page.getByRole('button', { name: 'Discard' }).last().click();
  await page.getByText(/Good (morning|afternoon|evening)/).first().waitFor({ timeout: 30000 });
}

async function shot(name) {
  await page.screenshot({ path: `${out}/${name}.png` });
}

await signIn();
const token = await apiToken();
const accounts = await api('/api/v1/accounts', token);
const account = (accounts.content ?? accounts).find((a) => /Paper Moon Current/.test(a.name));
const batchesNow = async () => {
  const page0 = await api(`/api/v1/accounts/${account.id}/imports?size=100`, token);
  return page0.content ?? page0;
};
const before = (await batchesNow()).length;

// A. A normal upload: header present, a v4 UUID, preflight allows it.
await startUpload();
await reviewReady();
await shot('a-review');
check('A1 upload carries Idempotency-Key', uploads[0]?.uuid, uploads[0]?.uuid);
check('A2 key is a version 4 UUID', UUID.test(uploads[0]?.uuid ?? ''));
const pre = preflights.find((p) => p.status);
check(
  'A3 CORS preflight asked for idempotency-key and was allowed',
  pre &&
    pre.status >= 200 && pre.status < 300 &&
    /idempotency-key/i.test(pre.headers['Access-Control-Request-Headers'] ?? pre.headers['access-control-request-headers'] ?? '') &&
    /idempotency-key/i.test(pre.allowHeaders ?? ''),
  pre && { status: pre.status, requested: pre.headers['Access-Control-Request-Headers'] ?? pre.headers['access-control-request-headers'], allowed: pre.allowHeaders },
);
await discardAndLeave();

// B. Lost response: the browser's own upload reaches the server, which commits it; the answer is
// then dropped on the way back (CDP Fetch at the response stage), as on a broken connection.
const lost = [];
let dropNext = true;
await cdp.send('Fetch.enable', {
  patterns: [{ urlPattern: '*/api/v1/accounts/*/imports', requestStage: 'Response' }],
});
cdp.on('Fetch.requestPaused', async (e) => {
  if (e.request.method === 'POST' && dropNext) {
    dropNext = false;
    const { body, base64Encoded } = await cdp.send('Fetch.getResponseBody', { requestId: e.requestId });
    const text = base64Encoded ? Buffer.from(body, 'base64').toString('utf8') : body;
    lost.push({ status: e.responseStatusCode, body: JSON.parse(text) });
    await cdp.send('Fetch.failRequest', { requestId: e.requestId, errorReason: 'ConnectionReset' });
  } else {
    await cdp.send('Fetch.continueRequest', { requestId: e.requestId });
  }
});
const firstB = uploads.length;
const beforeB = await batchesNow();
const countBeforeB = beforeB.length;
const beforeBIds = new Set(beforeB.map((b) => b.id));
await startUpload();
const retry = page.getByRole('button', { name: 'Try again' });
await retry.waitFor({ timeout: 30000 });
await shot('b-lost-response');
const bannerText = (await page.locator('body').innerText()).slice(0, 1500);
check('B1 server answered 201 to the lost attempt', lost[0]?.status === 201, lost[0]?.status);
const message = "The connection dropped before the server answered. Try again: your statement won't be imported twice.";
check('B2 app explains the dropped connection and offers Try again', bannerText.includes(message), bannerText.split('\n').filter((l) => /connection|wrong|Try again/i.test(l)).slice(0, 3));
await retry.click();
await reviewReady();
await shot('b-after-retry');
const bKeys = uploads.slice(firstB).map((u) => u.uuid);
check('B3 retry reused the same key', bKeys.length === 2 && bKeys[0] === bKeys[1] && UUID.test(bKeys[0]), bKeys);
check('B4 key differs from scenario A', bKeys[0] !== uploads[0].uuid);
const afterB = await batchesNow();
const newInB = afterB.length - countBeforeB;
check('B5 exactly one batch exists for the lost upload and its retry', newInB === 1, { newBatches: newInB });
const newBatch = afterB.find((b) => !beforeBIds.has(b.id));
check(
  'B6 the batch the retry shows is the one the lost attempt created',
  newBatch && newBatch.id === lost[0]?.body?.id,
  { lostAttemptBatchId: lost[0]?.body?.id, batchInAccount: newBatch?.id },
);
results.replayResponseBody = lost[0]?.body ? { id: lost[0].body.id, status: lost[0].body.status } : null;
await cdp.send('Fetch.disable');
await discardAndLeave();

// C. The next upload is a new logical create: a new key.
const firstC = uploads.length;
await startUpload();
await reviewReady();
check('C1 a new upload gets a new key', uploads[firstC]?.uuid && !bKeys.includes(uploads[firstC].uuid) && uploads[firstC].uuid !== uploads[0].uuid, uploads[firstC]?.uuid);
await discardAndLeave();

const after = await batchesNow();
results.batches = { before, after: after.length, statuses: after.map((b) => b.status) };
check('D1 three uploads made exactly three batches', after.length - before === 3, results.batches);
check('D2 no page errors', errors.filter((e) => !/net::ERR_CONNECTION_RESET|Failed to load resource/.test(e)).length === 0, errors);
results.uploads = uploads;
results.preflights = preflights.map(({ status, allowHeaders }) => ({ status, allowHeaders }));
writeFileSync(`${out}/results.json`, JSON.stringify(results, null, 2));
await browser.close();
process.exit(Object.values(results.checks).every((c) => c.ok) ? 0 : 1);
