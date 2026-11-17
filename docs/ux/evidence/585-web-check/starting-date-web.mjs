// #585 / PR #596: S6 step 2 keeps the starting date on or after the opening date from step 1.
// The server rejects an opening balance before the opening date (422); the app now limits the
// picker and disables Save. Credentials from the environment:
//   TMW_EMAIL=… TMW_PASSWORD=… OUT=<dir> node starting-date-web.mjs (web app on localhost:8091)
import { join } from "node:path";
import { chromium } from "playwright-core";
const APP = "http://localhost:8091",
  OUT = process.env.OUT;
const { TMW_EMAIL, TMW_PASSWORD } = process.env;
const browser = await chromium.launch({ channel: "chrome", headless: true });
const page = await (
  await browser.newContext({
    locale: "en-GB",
    viewport: { width: 1280, height: 900 },
  })
).newPage();
const errs = [];
page.on("response", async (r) => {
  if (r.url().includes("opening-balance") && r.request().method() === "POST")
    errs.push(`${r.status()} ${await r.text()}`);
});
await page.goto(`${APP}/`);
await page.getByLabel("Email").first().fill(TMW_EMAIL);
await page.getByLabel("Password").first().fill(TMW_PASSWORD);
await page.getByRole("button", { name: "Sign in" }).click();
await page
  .getByRole("button", { name: "Refresh overview" })
  .waitFor({ timeout: 20000 });
const name = `Step2 ${Date.now().toString(36).slice(-4)}`;
await page.getByRole("tab", { name: "+ Add portfolio" }).click();
await page.getByRole("radio", { name: /Create your own/ }).click();
await page.getByLabel("Portfolio name").fill(name);
await page.getByRole("button", { name: "Create portfolio" }).click();
await page.getByText(`Add an account to ${name}`).waitFor({ timeout: 15000 });
await page.getByLabel("Opened on (optional)").fill("2025-06-01");
await page.getByRole("button", { name: "Add account" }).click();
await page.getByText("Where does the value start?").waitFor({ timeout: 15000 });
console.log(
  "step2 date inputs",
  await page
    .locator("input[type=date]")
    .evaluateAll((e) =>
      e.map(
        (x) =>
          `${x.getAttribute("aria-label")} min=${x.min} max=${x.max} value=${x.value}`,
      ),
    ),
);
await page.getByLabel("Opening balance").fill("1000");
await page.locator("input[type=date]").first().fill("2025-01-01");
await page.waitForTimeout(400);
console.log(
  "save disabled for 2025-01-01:",
  await page.getByRole("button", { name: "Save starting value" }).isDisabled(),
);
await page.screenshot({
  path: join(OUT, "desktop-11-step2-before-opening-date.png"),
});
await page.locator("input[type=date]").first().fill("2025-06-01");
await page.getByRole("button", { name: "Save starting value" }).click();
await page.waitForTimeout(2500);
console.log("server:", errs);
const t = await page.locator("body").innerText();
const i = t.indexOf("Where does the value start?");
console.log("UI:", t.slice(i, i + 500).replace(/\n/g, " | "));
await browser.close();
