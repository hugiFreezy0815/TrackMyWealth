import { appendFileSync } from 'node:fs';
import { totp } from './totp.mjs';
const API = process.env.TMW_API;
const post = async (path, body, token) => {
  const r = await fetch(API + path, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: 'Bearer ' + token } : {}) }, body: JSON.stringify(body) });
  const j = await r.json().catch(() => null);
  if (!r.ok) throw new Error(`${path} ${r.status} ${JSON.stringify(j)}`);
  return j;
};
const login = await post('/api/v1/auth/login', { email: process.env.TMW_EMAIL, password: process.env.TMW_PASSWORD });
const token = login.tokens.accessToken;
const e = await post('/api/v1/users/me/mfa/enroll', { password: process.env.TMW_PASSWORD }, token);
console.log(e.otpauthUri.replace(/secret=[^&]+/, 'secret=<hidden>'));
await post('/api/v1/users/me/mfa/confirm', { code: totp(e.secret) }, token);
appendFileSync(process.argv[2], `TMW_TOTP_SECRET=${e.secret}\n`);
const again = await post('/api/v1/auth/login', { email: process.env.TMW_EMAIL, password: process.env.TMW_PASSWORD });
console.log('mfaRequired after enrolment:', again.mfaRequired);
