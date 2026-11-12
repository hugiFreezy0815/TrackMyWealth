// #556 check: a member without MFA who has an account, and a first-run member (no portfolios).
import { randomBytes } from 'node:crypto';
import { appendFileSync } from 'node:fs';
const API = process.env.TMW_API;
async function call(method, path, body, token) {
  const r = await fetch(API + path, { method, headers: { 'content-type': 'application/json', ...(token ? { authorization: 'Bearer ' + token } : {}) }, body: body && JSON.stringify(body) });
  const j = await r.json().catch(() => null);
  if (!r.ok) throw new Error(`${method} ${path} ${r.status} ${JSON.stringify(j)}`);
  return j;
}
const adminLogin = await call('POST', '/api/v1/auth/login', { email: 'admin@example.test', password: process.env.TMW_ADMIN_PASSWORD });
const admin = adminLogin.tokens.accessToken;
const make = async (email) => {
  const password = randomBytes(18).toString('base64url');
  await call('POST', '/api/v1/admin/users', { email, password, role: 'STANDARD_USER', language: 'EN' }, admin);
  return { email, password, token: (await call('POST', '/api/v1/auth/login', { email, password })).tokens.accessToken };
};
const plain = await make('plain@example.test');
const p = await call('POST', '/api/v1/portfolios', { name: 'UBS', country: 'CH', institutionType: 'BANK', portfolioCurrency: 'CHF' }, plain.token);
const acc = await call('POST', '/api/v1/accounts', { portfolioId: p.id, name: 'Privatkonto', accountType: 'CASH', nativeCurrency: 'CHF' }, plain.token);
const fresh = await make('fresh@example.test');
appendFileSync(process.argv[2], `PLAIN_EMAIL=${plain.email}\nPLAIN_PASSWORD=${plain.password}\nPLAIN_ACCOUNT=${acc.id}\nFRESH_EMAIL=${fresh.email}\nFRESH_PASSWORD=${fresh.password}\n`);
console.log('plain account', acc.id);
