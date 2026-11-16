// #585 / PR #596: signed-in web check of the account opening date (S6 Add account, S3 Account
// detail) plus axe. Runs against a local stack seeded with scripts/seed_demo.py; credentials come
// from the environment, never from this file:
//   TMW_EMAIL=… TMW_PASSWORD=… APP=http://localhost:8091 API=http://127.0.0.1:8090 OUT=<dir> \
//     node opening-date-web.mjs
import AxeBuilder from "@axe-core/playwright";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright-core";

const APP = process.env.APP ?? "http://localhost:8091";
const API = process.env.API ?? "http://127.0.0.1:8090";
const OUT = process.env.OUT ?? ".";
const { TMW_EMAIL, TMW_PASSWORD } = process.env;
if (!TMW_EMAIL || !TMW_PASSWORD)
  throw new Error("Set TMW_EMAIL and TMW_PASSWORD");

const results = [];
const axeReport = {};
const log = [];
const check = (name, pass, detail = "") => {
  results.push({ name, pass: Boolean(pass), detail });
  console.log(
    `${pass ? "PASS" : "FAIL"}  ${name}${detail ? `  (${detail})` : ""}`,
  );
};

// ---- API helpers (the member's own token, for the concurrent edit and the history check) ----
let token;
async function api(method, path, { body, version } = {}) {
  const response = await fetch(`${API}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      ...(version === undefined ? {} : { "If-Match": `"${version}"` }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}
async function apiLogin() {
  const response = await fetch(`${API}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: TMW_EMAIL, password: TMW_PASSWORD }),
  });
  token = (await response.json()).tokens.accessToken;
}
const historyWarnings = async (period) =>
  (await api("GET", `/api/v1/net-worth/history?period=${period}`)).body
    .warningCodes;

// ---- UI helpers ----
async function axe(page, key) {
  const { violations } = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  axeReport[key] = violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    nodes: v.nodes.length,
    targets: v.nodes.slice(0, 3).map((n) => n.target.join(" ")),
  }));
  check(
    `axe ${key}: 0 violations`,
    violations.length === 0,
    violations.map((v) => v.id).join(", "),
  );
}
async function shot(page, file) {
  await page.waitForTimeout(400);
  await page.screenshot({ path: join(OUT, file) });
}
async function signIn(page) {
  await page.goto(`${APP}/`);
  await page.getByLabel("Email").first().fill(TMW_EMAIL);
  await page.getByLabel("Password").first().fill(TMW_PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page
    .getByRole("button", { name: "Sign in" })
    .waitFor({ state: "detached" });
  await page
    .getByRole("button", { name: "Refresh overview" })
    .waitFor({ timeout: 20000 });
}
const phoneToday = (page) =>
  page.evaluate(() => {
    const d = new Date();
    const pad = (n) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  });
const dateInput = (page) => page.getByLabel("Opened on (optional)");

async function createPortfolio(page, name) {
  await page.getByRole("link", { name: "Overview", exact: true }).click();
  await page.getByRole("tab", { name: "+ Add portfolio" }).click();
  await page
    .getByRole("radio", { name: /Create your own/ })
    .click({ timeout: 10000 });
  await page.getByLabel("Portfolio name").fill(name);
  await page.getByRole("button", { name: "Create portfolio" }).click();
  await page.getByText(`Add an account to ${name}`).waitFor({ timeout: 15000 });
}
async function openAccount(page, portfolio, account) {
  await page.getByRole("link", { name: "Overview", exact: true }).click();
  await page
    .getByRole("button", { name: portfolio, exact: true })
    .first()
    .click();
  await page
    .getByRole("button", { name: new RegExp(`^${account},`) })
    .first()
    .click();
  await page.getByLabel("Account actions").waitFor({ timeout: 15000 });
}
async function openEditor(page) {
  await page.getByLabel("Account actions").click();
  await page.getByRole("button", { name: "Edit opening date" }).click();
  await page.getByRole("button", { name: "Save opening date" }).waitFor();
  await page.waitForTimeout(500);
}

// ---- run ----
await apiLogin();
const browser = await chromium.launch({ channel: "chrome", headless: true });
const consoleErrors = [];
const requests = [];

async function newPage(viewport) {
  const context = await browser.newContext({ locale: "en-GB", viewport });
  const page = await context.newPage();
  // The deliberate 412 of the conflict step is logged by the browser as a failed resource load.
  page.on(
    "console",
    (m) =>
      m.type() === "error" &&
      !/status of 412/.test(m.text()) &&
      consoleErrors.push(m.text()),
  );
  page.on("request", (r) => {
    if (
      !r.url().startsWith(API.replace("127.0.0.1", "localhost")) &&
      !r.url().includes(":8090")
    )
      return;
    if (r.method() === "OPTIONS") return;
    requests.push({
      method: r.method(),
      path: new URL(r.url()).pathname + new URL(r.url()).search,
      ifMatch: r.headers()["if-match"],
      body: r.postData() ? JSON.parse(r.postData()) : undefined,
      at: Date.now(),
    });
  });
  return page;
}
const lastRequest = (method, pattern) =>
  [...requests]
    .reverse()
    .find((r) => r.method === method && pattern.test(r.path));

const stamp = Date.now().toString(36).slice(-4);
const PORTFOLIO = `Opening date ${stamp}`;
const ACCOUNT = `Opened ${stamp}`;

try {
  // ===== Desktop 1280 =====
  const page = await newPage({ width: 1280, height: 900 });
  await signIn(page);
  const before1Y = await historyWarnings("1Y");
  check(
    "Before: 1Y history warns INCEPTION_UNCERTAIN (accounts without opening date)",
    before1Y.includes("INCEPTION_UNCERTAIN"),
    before1Y.join(","),
  );

  // --- S6 Add account ---
  await createPortfolio(page, PORTFOLIO);
  const today = await phoneToday(page);
  check(
    "S6: date field is optional and ends at the phone's today",
    (await dateInput(page).getAttribute("max")) === today,
    `max=${await dateInput(page).getAttribute("max")}`,
  );
  check(
    "S6: hint about earlier bookings shown",
    await page
      .getByText("For value history, an earlier booking takes precedence.")
      .isVisible(),
  );
  await page.getByLabel("Account name").fill(ACCOUNT);
  await dateInput(page).fill("2099-01-01");
  await page.waitForTimeout(300);
  check(
    "S6: a future date shows the field error",
    await page.getByText("Choose today or an earlier date.").isVisible(),
  );
  check(
    "S6: Add account is disabled for a future date",
    await page.getByRole("button", { name: "Add account" }).isDisabled(),
  );
  await shot(page, "desktop-1-add-account-future.png");
  await dateInput(page).fill(today);
  await page.waitForTimeout(300);
  check(
    "S6: the phone's today is accepted",
    !(await page.getByText("Choose today or an earlier date.").isVisible()) &&
      (await page.getByRole("button", { name: "Add account" }).isEnabled()),
  );
  await dateInput(page).fill("2025-01-01");
  await shot(page, "desktop-2-add-account.png");
  await axe(page, "desktop S6 Add account");
  await page.getByRole("button", { name: "Add account" }).click();
  await page
    .getByText("Where does the value start?")
    .waitFor({ timeout: 15000 });
  const post = lastRequest("POST", /^\/api\/v1\/accounts$/);
  check(
    "S6: POST /accounts carries openedAt",
    post?.body?.openedAt === "2025-01-01",
    JSON.stringify(post?.body),
  );

  // --- S3 Account detail ---
  await openAccount(page, PORTFOLIO, ACCOUNT);
  check(
    "S3: header shows the saved date",
    await page.getByText("Opened on 1 Jan 2025").isVisible(),
  );
  await shot(page, "desktop-3-account-detail.png");

  await openEditor(page);
  check(
    "S3: editor shows the saved date",
    (await dateInput(page).inputValue()) === "2025-01-01",
  );
  check(
    "S3: Save disabled while unchanged",
    await page.getByRole("button", { name: "Save opening date" }).isDisabled(),
  );
  await dateInput(page).fill("2024-06-30");
  await shot(page, "desktop-4-edit-dialog.png");
  await axe(page, "desktop S3 Edit opening date");
  const beforeSave = Date.now();
  await page.getByRole("button", { name: "Save opening date" }).click();
  await page.getByText("Opened on 30 Jun 2024").waitFor({ timeout: 10000 });
  const put = lastRequest("PUT", /^\/api\/v1\/accounts\/[^/]+$/);
  const accountId = put.path.split("/").pop();
  const saved = (await api("GET", `/api/v1/accounts/${accountId}`)).body;
  check(
    "S3: PUT carries If-Match",
    /^"\d+"$/.test(put.ifMatch ?? ""),
    put.ifMatch,
  );
  check(
    "S3: PUT keeps the other fields",
    put.body.name === ACCOUNT &&
      put.body.accountType &&
      put.body.nativeCurrency &&
      put.body.openedAt === "2024-06-30",
    JSON.stringify(put.body),
  );
  check(
    "S3: server stores the date as picked",
    saved.openedAt === "2024-06-30",
    saved.openedAt,
  );

  // History refetched: back on Overview the history is read again after the save.
  await page.getByRole("link", { name: "Overview", exact: true }).click();
  await page.getByRole("button", { name: "Refresh overview" }).waitFor();
  await page.waitForTimeout(2000);
  check(
    "S3: Overview history is read again after the save",
    requests.some(
      (r) =>
        r.method === "GET" &&
        r.path.startsWith("/api/v1/net-worth/history") &&
        r.at > beforeSave,
    ),
  );
  await page
    .getByRole("button", { name: PORTFOLIO, exact: true })
    .first()
    .click();
  await page.waitForTimeout(2000);
  check(
    "S3: Portfolio View history is read again after the save",
    requests.some(
      (r) =>
        r.method === "GET" &&
        /\/portfolios\/[^/]+\/history/.test(r.path) &&
        r.at > beforeSave,
    ),
  );

  // --- Conflict: another device renames the account while the editor is open ---
  await openAccount(page, PORTFOLIO, ACCOUNT);
  await openEditor(page);
  await dateInput(page).fill("2024-01-15");
  const current = (await api("GET", `/api/v1/accounts/${accountId}`)).body;
  const renamed = `${ACCOUNT} renamed`;
  const rename = await api("PUT", `/api/v1/accounts/${accountId}`, {
    version: current.version,
    body: {
      name: renamed,
      accountType: current.accountType,
      nativeCurrency: current.nativeCurrency,
      openedAt: current.openedAt,
    },
  });
  check(
    "Conflict setup: concurrent rename saved",
    rename.status === 200,
    String(rename.status),
  );
  await page.getByRole("button", { name: "Save opening date" }).click();
  await page
    .getByText("This item was changed meanwhile")
    .waitFor({ timeout: 10000 });
  check("Conflict: stale version shows the conflict dialog", true);
  await shot(page, "desktop-5-conflict.png");
  await page.getByRole("button", { name: "Review and save again" }).click();
  await page.getByRole("button", { name: "Save opening date" }).waitFor();
  await page.waitForTimeout(800);
  check(
    "Conflict: the draft date is kept for review",
    (await dateInput(page).inputValue()) === "2024-01-15",
  );
  await page.getByRole("button", { name: "Save opening date" }).click();
  await page.getByText("Opened on 15 Jan 2024").waitFor({ timeout: 10000 });
  const resave = lastRequest("PUT", /^\/api\/v1\/accounts\/[^/]+$/);
  check(
    "Conflict: resave uses the new version and keeps the rename",
    resave.ifMatch === `"${rename.body.version}"` &&
      resave.body.name === renamed,
    `${resave.ifMatch} ${resave.body.name}`,
  );

  // --- Clear ---
  await openEditor(page);
  await dateInput(page).fill("");
  await page.getByRole("button", { name: "Save opening date" }).click();
  await page
    .getByText(/^Opened on \d/)
    .waitFor({ state: "detached", timeout: 10000 });
  const cleared = (await api("GET", `/api/v1/accounts/${accountId}`)).body;
  check(
    "Clear: the date is removed",
    cleared.openedAt === null && !lastRequest("PUT", /accounts/).body.openedAt,
  );
  await shot(page, "desktop-6-cleared.png");

  // --- Archived account: the edit is disabled ---
  await page.getByLabel("Account actions").click();
  await page.getByRole("button", { name: "Archive account" }).click();
  await page.getByText(/^Archive .* It stays in your history/).waitFor();
  await page.getByRole("button", { name: "Archive account" }).last().click();
  await page
    .getByText(/^Archived on /)
    .first()
    .waitFor({ timeout: 10000 });
  await page.waitForTimeout(800);
  await page.getByLabel("Account actions").click();
  const edit = page.getByRole("button", { name: "Edit opening date" });
  await edit.waitFor();
  check("Archived: Edit opening date is disabled", await edit.isDisabled());
  await shot(page, "desktop-7-archived-actions.png");
  await page.keyboard.press("Escape");
  await page.waitForTimeout(500);
  await page.getByRole("button", { name: "Restore account" }).first().click();
  await page
    .getByText(/^Archived on /)
    .first()
    .waitFor({ state: "detached", timeout: 10000 });
  check("Archived cleanup: restored in the app", true);

  // --- API rules: phone date in any zone, far future rejected ---
  const zoneToday = (zone) =>
    new Intl.DateTimeFormat("en-CA", { timeZone: zone }).format(new Date());
  const kiritimati = zoneToday("Pacific/Kiritimati");
  let latest = (await api("GET", `/api/v1/accounts/${accountId}`)).body;
  const base = {
    name: latest.name,
    accountType: latest.accountType,
    nativeCurrency: latest.nativeCurrency,
  };
  const ahead = await api("PUT", `/api/v1/accounts/${accountId}`, {
    version: latest.version,
    body: { ...base, openedAt: kiritimati },
  });
  check(
    `API: phone today in the earliest zone (${kiritimati}) is accepted and stored as sent`,
    ahead.status === 200 && ahead.body.openedAt === kiritimati,
    `${ahead.status} ${ahead.body?.openedAt}`,
  );
  const far = new Date(Date.now() + 3 * 864e5).toISOString().slice(0, 10);
  const future = await api("PUT", `/api/v1/accounts/${accountId}`, {
    version: ahead.body.version,
    body: { ...base, openedAt: far },
  });
  check(
    `API: a date not yet today anywhere (${far}) gets 400 VALIDATION_FAILED`,
    future.status === 400 &&
      future.body.code === "VALIDATION_FAILED" &&
      future.body.errors[0].field === "openedAt",
    `${future.status} ${future.body?.errors?.[0]?.message}`,
  );
  // The German message needs a member whose stored language is DE: see de-message.mjs.

  // AC1 (the 1Y chip with and without an opening date) is ac1-web.mjs.
  await page.context().close();

  // ===== Phone 390 =====
  const phone = await newPage({ width: 390, height: 844 });
  await signIn(phone);
  await createPortfolio(phone, `${PORTFOLIO} phone`);
  await phone.getByLabel("Account name").fill(`${ACCOUNT} phone`);
  await dateInput(phone).fill("2025-01-01");
  await dateInput(phone).scrollIntoViewIfNeeded();
  await shot(phone, "phone-1-add-account.png");
  await axe(phone, "phone S6 Add account");
  const hScroll = await phone.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth,
  );
  check("Phone S6: no horizontal scroll", !hScroll);
  await openAccount(phone, PORTFOLIO, renamed);
  await shot(phone, "phone-2-account-detail.png");
  await openEditor(phone);
  await shot(phone, "phone-3-edit-dialog.png");
  await axe(phone, "phone S3 Edit opening date");
  await phone.context().close();
} catch (error) {
  check(
    "Run completed without an exception",
    false,
    String(error).slice(0, 400),
  );
} finally {
  check(
    "No console error",
    consoleErrors.length === 0,
    consoleErrors.slice(0, 3).join(" | "),
  );
  await browser.close();
  writeFileSync(
    join(OUT, "results.txt"),
    results
      .map(
        (r) =>
          `${r.pass ? "PASS" : "FAIL"}  ${r.name}${r.detail ? `  (${r.detail})` : ""}`,
      )
      .join("\n") + `\n\n${log.join("\n")}\n`,
  );
  writeFileSync(
    join(OUT, "axe-results.json"),
    JSON.stringify(axeReport, null, 2),
  );
}
