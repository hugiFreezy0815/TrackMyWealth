// #585 AC1 in the app: account A holds money before the 1Y period, account B's first evidence is
// 30 days ago. Without B's opening date the 1Y change is neutral (INCEPTION_UNCERTAIN); with it,
// the chip shows a percentage. Own CHF portfolio, so no FX or demo warnings interfere.
//   TMW_EMAIL=… TMW_PASSWORD=… APP=… API=… OUT=<dir> node ac1-web.mjs
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright-core";
const APP = process.env.APP ?? "http://localhost:8091",
  API = process.env.API ?? "http://127.0.0.1:8090",
  OUT = process.env.OUT ?? ".";
const { TMW_EMAIL, TMW_PASSWORD } = process.env;
const token = (
  await (
    await fetch(`${API}/api/v1/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: TMW_EMAIL, password: TMW_PASSWORD }),
    })
  ).json()
).tokens.accessToken;
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
const NAME = `AC1 check ${Date.now().toString(36).slice(-4)}`;
const portfolio = await call("POST", "/api/v1/portfolios", {
  name: NAME,
  country: "CH",
  portfolioCurrency: "CHF",
  institutionType: "BANK",
});
const a = await call("POST", "/api/v1/accounts", {
  portfolioId: portfolio.id,
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
const b = await call("POST", "/api/v1/accounts", {
  portfolioId: portfolio.id,
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
const page = await (
  await browser.newContext({
    locale: "en-GB",
    viewport: { width: 1280, height: 900 },
  })
).newPage();
await page.goto(`${APP}/`);
await page.getByLabel("Email").first().fill(TMW_EMAIL);
await page.getByLabel("Password").first().fill(TMW_PASSWORD);
await page.getByRole("button", { name: "Sign in" }).click();
await page
  .getByRole("button", { name: "Refresh overview" })
  .waitFor({ timeout: 20000 });

async function capture(label) {
  const h = await call(
    "GET",
    `/api/v1/portfolios/${portfolio.id}/history?period=1Y`,
  );
  await page.getByRole("link", { name: "Overview", exact: true }).click();
  await page.getByRole("button", { name: NAME, exact: true }).first().click();
  await page.getByRole("button", { name: "Refresh portfolio" }).click();
  await page.getByRole("radio", { name: /^1Y/ }).click();
  await page.waitForTimeout(2500);
  const text = await page.locator("body").innerText();
  const i = text.indexOf("past year");
  const excerpt = text
    .slice(Math.max(0, i - 60), i + 110)
    .replace(/\n/g, " | ");
  lines.push(
    `${label}: warningCodes=${JSON.stringify(h.warningCodes)} percent=${h.change.percent}\n  UI: ${excerpt}`,
  );
  await page.screenshot({ path: join(OUT, `desktop-10-ac1-${label}.png`) });
}
await capture("without-opening-date");
// The member enters B's opening date in the app (S3): first a date before B's first evidence,
// then the date of that evidence.
async function setOpeningDate(date) {
  await page
    .getByRole("button", { name: /^Recent,/ })
    .first()
    .click();
  await page.getByLabel("Account actions").click();
  await page.getByRole("button", { name: "Edit opening date" }).click();
  await page.getByLabel("Opened on (optional)").fill(date);
  await page.getByRole("button", { name: "Save opening date" }).click();
  await page
    .getByRole("button", { name: "Save opening date" })
    .waitFor({ state: "detached", timeout: 10000 });
}
await setOpeningDate(day(-400));
await capture("opened-before-period");
await setOpeningDate(day(-30));
await capture("opened-on-first-evidence");
await browser.close();
writeFileSync(join(OUT, "ac1-results.txt"), lines.join("\n") + "\n");
console.log(lines.join("\n"));
