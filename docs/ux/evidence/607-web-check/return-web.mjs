// #607 in the app: the change chip shows the CHF change plus the period return, and "since
// {date}" when the return starts after the period. Synthetic data on an otherwise empty
// workspace, entered as the member through the API:
//   1. #585's AC1 scenario: A holds CHF 2,000 since 400 days ago, B's first balance is CHF 1,000
//      30 days ago. 1Y return 0.0 % without B's opening date, with one before the period and with
//      one on the day of the first balance.
//   2. AC2: a gold custom asset worth 2,000 grows to 2,100 by today.
//   3. AC3: a portfolio whose only account is tracked since 34 days ago shows "since {date}".
//   4. The same in German (a second member with language DE) and at phone width.
//   TMW_EMAIL=… TMW_PASSWORD=… TMW_DE_EMAIL=… TMW_DE_PASSWORD=… APP=… API=… OUT=<dir> \
//     node return-web.mjs
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import AxeBuilder from "@axe-core/playwright";
import { chromium } from "playwright-core";

const APP = process.env.APP ?? "http://localhost:8095",
  API = process.env.API ?? "http://127.0.0.1:8094",
  OUT = process.env.OUT ?? ".";
const { TMW_EMAIL, TMW_PASSWORD, TMW_DE_EMAIL, TMW_DE_PASSWORD } = process.env;

async function login(email, password) {
  const r = await fetch(`${API}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  return (await r.json()).tokens.accessToken;
}
const token = await login(TMW_EMAIL, TMW_PASSWORD);
const call = async (m, p, b, v) => {
  const r = await fetch(API + p, {
    method: m,
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      ...(v !== undefined ? { "If-Match": `"${v}"` } : {}),
    },
    body: b ? JSON.stringify(b) : undefined,
  });
  const x = await r.text();
  if (r.status >= 300) throw new Error(`${m} ${p} ${r.status} ${x}`);
  return x ? JSON.parse(x) : null;
};
const day = (offset) =>
  new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Zurich" }).format(
    new Date(Date.now() + offset * 864e5),
  );

// --- Data --------------------------------------------------------------------------------------
const bank = await call("POST", "/api/v1/portfolios", {
  name: "Bank",
  country: "CH",
  portfolioCurrency: "CHF",
  institutionType: "BANK",
});
const a = await call("POST", "/api/v1/accounts", {
  portfolioId: bank.id,
  name: "Established",
  accountType: "CASH",
  nativeCurrency: "CHF",
  openedAt: day(-400),
});
await call("POST", `/api/v1/accounts/${a.id}/opening-balance`, {
  date: day(-400),
  balance: "2000",
  currency: "CHF",
});
let b = await call("POST", "/api/v1/accounts", {
  portfolioId: bank.id,
  name: "Recent",
  accountType: "CASH",
  nativeCurrency: "CHF",
});
await call("POST", `/api/v1/accounts/${b.id}/opening-balance`, {
  date: day(-30),
  balance: "1000",
  currency: "CHF",
});

const lines = [];
const browser = await chromium.launch({ channel: "chrome", headless: true });

async function signIn(email, password, viewport, locale = "en-GB") {
  const context = await browser.newContext({ locale, viewport });
  const page = await context.newPage();
  const errors = [];
  page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
  await page.goto(`${APP}/`);
  await page.locator('input[type="email"]').first().fill(email);
  await page.locator('input[type="password"]').first().fill(password);
  await page.keyboard.press("Enter");
  await page.locator('[role="radiogroup"]').first().waitFor({ timeout: 30000 });
  return { page, errors };
}

const desktop = await signIn(TMW_EMAIL, TMW_PASSWORD, { width: 1280, height: 900 });
const page = desktop.page;

async function chipText(p, rangeWords) {
  await p.waitForTimeout(2500);
  const text = await p.locator("body").innerText();
  const i = Math.max(...rangeWords.map((w) => text.indexOf(w)));
  return text.slice(Math.max(0, i - 70), i + 40).replace(/\n/g, " | ");
}

async function overview(p, label, period = "1Y", words = ["past year"]) {
  const scope = `/api/v1/net-worth/history?period=${period}`;
  const h = await call("GET", scope);
  await p.getByRole("button", { name: "Refresh overview" }).click();
  await p.getByRole("radio", { name: new RegExp(`^${period}`) }).click();
  const ui = await chipText(p, words);
  lines.push(
    `${label}: amount=${h.change.amount} percent=${h.change.percent}` +
      ` returnPercent=${h.change.returnPercent} returnFrom=${h.change.returnFrom}` +
      ` from=${h.from} warningCodes=${JSON.stringify(h.warningCodes)}\n  UI: ${ui}`,
  );
  await p.screenshot({ path: join(OUT, `desktop-${label}.png`) });
}

// 1. AC1 without and with B's opening date.
await overview(page, "1-ac1-without-opening-date");
for (const [label, openedAt] of [
  ["2-ac1-opened-before-period", day(-400)],
  ["3-ac1-opened-on-first-balance", day(-30)],
]) {
  b = await call(
    "PUT",
    `/api/v1/accounts/${b.id}`,
    { name: b.name, accountType: "CASH", nativeCurrency: "CHF", openedAt },
    b.version,
  );
  await overview(page, label);
}

// 2. AC2: gold grows from 2,000 to 2,100.
const gold = await call("POST", "/api/v1/accounts", {
  name: "Gold",
  accountType: "CUSTOM_ASSET",
  nativeCurrency: "CHF",
  customAssetType: "PRECIOUS_METAL",
});
await call("POST", `/api/v1/accounts/${gold.id}/valuations`, {
  valuationDate: day(-400),
  value: "2000",
});
await call("POST", `/api/v1/accounts/${gold.id}/valuations`, {
  valuationDate: day(0),
  value: "2100",
});
await overview(page, "4-ac2-gold-grows");

// 3. AC3: a portfolio tracked since 34 days ago.
const neo = await call("POST", "/api/v1/portfolios", {
  name: "Neo",
  country: "CH",
  portfolioCurrency: "CHF",
  institutionType: "BANK",
});
const neoCash = await call("POST", "/api/v1/accounts", {
  portfolioId: neo.id,
  name: "Neo cash",
  accountType: "CASH",
  nativeCurrency: "CHF",
});
await call("POST", `/api/v1/accounts/${neoCash.id}/opening-balance`, {
  date: day(-34),
  balance: "5000",
  currency: "CHF",
});

async function portfolioView(p, label, words, name = "Neo") {
  const h = await call("GET", `/api/v1/portfolios/${neo.id}/history?period=1Y`);
  await p.getByRole("link", { name: /^(Overview|Übersicht)$/ }).click();
  await p.getByRole("button", { name: /^(Refresh overview|Übersicht aktualisieren)$/ }).click();
  await p.getByRole("button", { name, exact: true }).first().click();
  await p.getByRole("radio", { name: /^1(Y|J)/ }).click();
  const ui = await chipText(p, words);
  lines.push(
    `${label}: amount=${h.change.amount} returnPercent=${h.change.returnPercent}` +
      ` returnFrom=${h.change.returnFrom} from=${h.from}\n  UI: ${ui}`,
  );
  await p.screenshot({ path: join(OUT, `${label}.png`) });
}
await portfolioView(page, "desktop-5-ac3-portfolio-since", ["since "]);

// axe on the Overview with the return chip (WCAG 2.0/2.1 A and AA).
await page.getByRole("link", { name: "Overview", exact: true }).click();
await page.getByRole("button", { name: "Refresh overview" }).click();
await page.waitForTimeout(2000);
const axe = await new AxeBuilder({ page })
  .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
  .analyze();
writeFileSync(join(OUT, "axe-results.json"), JSON.stringify(axe.violations, null, 2));
lines.push(
  `axe: ${axe.violations.length} violation(s)` +
    (axe.violations.length ? `: ${axe.violations.map((v) => v.id).join(", ")}` : ""),
);

// 4. German and phone width.
const de = await signIn(TMW_DE_EMAIL, TMW_DE_PASSWORD, { width: 1280, height: 900 }, "de-CH");
await portfolioView(de.page, "desktop-6-de-portfolio-seit", ["seit "]);
const phone = await signIn(TMW_EMAIL, TMW_PASSWORD, { width: 390, height: 844 });
await phone.page.getByRole("radio", { name: /^1Y/ }).click();
await phone.page.waitForTimeout(2500);
const overflow = await phone.page.evaluate(
  () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
);
lines.push(`phone 390: horizontal overflow ${overflow}px`);
await phone.page.screenshot({ path: join(OUT, "phone-1-overview-1y.png") });

for (const [name, errors] of [
  ["desktop", desktop.errors],
  ["de", de.errors],
  ["phone", phone.errors],
]) {
  lines.push(`console errors (${name}): ${errors.length ? errors.join(" / ") : "none"}`);
}
await browser.close();
writeFileSync(join(OUT, "results.txt"), lines.join("\n") + "\n");
console.log(lines.join("\n"));
