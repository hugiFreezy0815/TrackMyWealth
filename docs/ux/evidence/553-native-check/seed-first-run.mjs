// #556 check: an empty workspace (only the automatic Personal assets portfolio) and one member.
import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
const API = process.env.TMW_API;
async function call(path, body, token) {
  const r = await fetch(API + path, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: 'Bearer ' + token } : {}) }, body: JSON.stringify(body) });
  const j = await r.json().catch(() => null);
  if (!r.ok) throw new Error(`${path} ${r.status} ${JSON.stringify(j)}`);
  return j;
}
const pw = () => randomBytes(18).toString('base64url');
const admin = await call('/api/v1/setup/administrator', { email: 'admin@example.test', password: pw(), workspaceName: null, currencyCode: 'CHF' });
const member = { email: 'fresh@example.test', password: pw() };
await call('/api/v1/admin/users', { ...member, role: 'STANDARD_USER', language: 'EN' }, admin.accessToken);
writeFileSync(process.argv[2], `FRESH_EMAIL=${member.email}\nFRESH_PASSWORD=${member.password}\n`);
const t = (await call('/api/v1/auth/login', member)).tokens.accessToken;
const list = await (await fetch(API + '/api/v1/portfolios', { headers: { authorization: 'Bearer ' + t } })).json();
console.log('portfolios', JSON.stringify(list).slice(0, 200));
