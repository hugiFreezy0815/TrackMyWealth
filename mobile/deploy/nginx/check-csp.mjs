// Loads pages of the served web export in headless Chrome and fails when the
// Content-Security-Policy blocks anything or the app does not render (#536).
// Usage: node check-csp.mjs BASE_URL [PATH ...]   (Chrome binary: $CHROME or google-chrome)
// Node 22+ only, no dependencies: it speaks the Chrome DevTools Protocol over WebSocket.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const [baseUrl, ...argPaths] = process.argv.slice(2);
if (!baseUrl) {
  console.error('Usage: node check-csp.mjs BASE_URL [PATH ...]');
  process.exit(2);
}
const paths = argPaths.length > 0 ? argPaths : ['/'];
const profile = mkdtempSync(join(tmpdir(), 'check-csp-'));
const chrome = spawn(
  process.env.CHROME ?? 'google-chrome',
  [
    '--headless=new',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-gpu',
    `--user-data-dir=${profile}`,
    '--remote-debugging-port=0',
    'about:blank',
  ],
  { stdio: 'ignore' },
);

async function browserEndpoint() {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const [port, path] = readFileSync(join(profile, 'DevToolsActivePort'), 'utf8').split('\n');
      return `ws://127.0.0.1:${port}${path}`;
    } catch {
      await delay(100);
    }
  }
  throw new Error('Chrome did not start');
}

function connect(url) {
  const socket = new WebSocket(url);
  const pending = new Map();
  const handlers = new Set();
  let nextId = 0;
  socket.addEventListener('message', ({ data }) => {
    const message = JSON.parse(data);
    if (message.id !== undefined) {
      const { resolve, reject } = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) reject(new Error(message.error.message));
      else resolve(message.result);
    } else {
      handlers.forEach((handler) => handler(message));
    }
  });
  const send = (method, params = {}, sessionId) =>
    new Promise((resolve, reject) => {
      nextId += 1;
      pending.set(nextId, { resolve, reject });
      socket.send(JSON.stringify({ id: nextId, method, params, sessionId }));
    });
  return new Promise((resolve, reject) => {
    socket.addEventListener('open', () =>
      resolve({ send, onEvent: (handler) => handlers.add(handler), close: () => socket.close() }),
    );
    socket.addEventListener('error', () => reject(new Error(`Cannot connect to ${url}`)));
  });
}

const RECORD_VIOLATIONS = `
  window.__cspViolations = [];
  document.addEventListener('securitypolicyviolation', (event) =>
    window.__cspViolations.push(event.violatedDirective + ' ' + (event.blockedURI || 'inline')));
`;

let failed = false;
try {
  const cdp = await connect(await browserEndpoint());
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
  const send = (method, params) => cdp.send(method, params, sessionId);
  const consoleErrors = [];
  cdp.onEvent(({ method, params, sessionId: from }) => {
    if (from === sessionId && method === 'Log.entryAdded' && params.entry.level === 'error') {
      consoleErrors.push(params.entry.text);
    }
  });
  await send('Page.enable');
  await send('Log.enable');
  await send('Page.addScriptToEvaluateOnNewDocument', { source: RECORD_VIOLATIONS });

  for (const path of paths) {
    consoleErrors.length = 0;
    await send('Page.navigate', { url: new URL(path, baseUrl).href });
    let result = { violations: [], rendered: false };
    // Wait until the app rendered its first screen (or give up after 15 s).
    for (let waited = 0; waited < 15000 && !result.rendered; waited += 250) {
      await delay(250);
      const { result: value } = await send('Runtime.evaluate', {
        expression: `JSON.stringify({
          violations: window.__cspViolations ?? [],
          rendered: document.body.innerText.trim().length > 0 })`,
        returnByValue: true,
      });
      result = JSON.parse(value.value);
    }
    await delay(1000);
    const { result: final } = await send('Runtime.evaluate', {
      expression: 'JSON.stringify(window.__cspViolations ?? [])',
      returnByValue: true,
    });
    const violations = [
      ...new Set([
        ...JSON.parse(final.value),
        ...consoleErrors.filter((text) => /Content Security Policy/i.test(text)),
      ]),
    ];
    if (violations.length > 0 || !result.rendered) {
      failed = true;
      console.error(`${path}: ${result.rendered ? 'rendered' : 'did not render'}`);
      violations.forEach((violation) => console.error(`  CSP violation: ${violation}`));
    } else {
      console.log(`${path}: rendered, no CSP violation`);
    }
  }
  cdp.close();
} catch (error) {
  failed = true;
  console.error(error.message);
} finally {
  chrome.kill();
  await delay(200);
  rmSync(profile, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
