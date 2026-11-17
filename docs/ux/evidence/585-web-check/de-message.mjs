// #585 / PR #596: the 400 for a future opening date is localized in the member's stored language.
// The admin creates a member with language DE (stored preference wins over Accept-Language).
//   CREDS=<seed_demo credentials file> node de-message.mjs (API on 127.0.0.1:8090)
import { readFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
const users = JSON.parse(readFileSync(process.env.CREDS)).users;
const API = "http://127.0.0.1:8090";
const post = (p, b, t) =>
  fetch(API + p, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(t ? { Authorization: `Bearer ${t}` } : {}),
    },
    body: JSON.stringify(b),
  }).then(async (r) => ({
    status: r.status,
    body: await r.json().catch(() => null),
  }));
const admin = await post("/api/v1/auth/login", {
  email: users.admin.email,
  password: users.admin.password,
});
if (admin.body?.mfaRequired) {
  console.log("admin needs MFA; skipped");
  process.exit(0);
}
const email = `de-check-${randomBytes(3).toString("hex")}@example.test`,
  password = randomBytes(12).toString("base64url");
const created = await post(
  "/api/v1/admin/users",
  {
    email,
    password,
    displayName: "DE check",
    role: "STANDARD_USER",
    language: "DE",
  },
  admin.body.tokens.accessToken,
);
console.log("create DE member", created.status, created.body?.code ?? "");
const login = await post("/api/v1/auth/login", { email, password });
const t = login.body.tokens?.accessToken;
const r = await fetch(`${API}/api/v1/accounts`, {
  method: "POST",
  headers: { "Content-Type": "application/json", Authorization: `Bearer ${t}` },
  body: JSON.stringify({
    name: "x",
    accountType: "CASH",
    nativeCurrency: "CHF",
    openedAt: new Date(Date.now() + 3 * 864e5).toISOString().slice(0, 10),
  }),
}).then((r) => r.json());
console.log(
  "DE member, future openedAt:",
  r.code,
  r.errors?.[0]?.field,
  "→",
  r.errors?.[0]?.message,
  "| detail:",
  r.detail,
);
