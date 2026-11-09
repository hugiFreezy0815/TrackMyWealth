// Seeds a fresh compose stack for the #492 web check: admin, one standard member (EN), a "DKB"
// portfolio (EUR) with a EUR "Giro" cash account. Writes the member's credentials to <envFile>.
import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const API = 'http://localhost:8080';
const envFile = process.argv[2];

async function call(method, path, body, token) {
  const res = await fetch(API + path, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: 'Bearer ' + token } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => null);
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${JSON.stringify(json)}`);
  return json;
}

const password = () => randomBytes(18).toString('base64url');
const admin = { email: 'admin@example.test', password: password() };
const member = { email: 'member@example.test', password: password() };
writeFileSync(
  envFile,
  `TMW_ADMIN_PASSWORD=${admin.password}\nTMW_EMAIL=${member.email}\nTMW_PASSWORD=${member.password}\n`,
);

const setup = await call('POST', '/api/v1/setup/administrator', {
  ...admin,
  workspaceName: 'Synthetic household',
  currencyCode: 'EUR',
});
await call(
  'POST',
  '/api/v1/admin/users',
  { ...member, role: 'STANDARD_USER', language: 'EN' },
  setup.accessToken,
);
const login = await call('POST', '/api/v1/auth/login', member);
const token = login.tokens.accessToken;
const portfolio = await call(
  'POST',
  '/api/v1/portfolios',
  { name: 'DKB', country: 'DE', institutionType: 'BANK', portfolioCurrency: 'EUR' },
  token,
);
const account = await call(
  'POST',
  '/api/v1/accounts',
  { portfolioId: portfolio.id, name: 'Giro', accountType: 'CASH', nativeCurrency: 'EUR' },
  token,
);
console.log(JSON.stringify({ portfolio: portfolio.id, account: account.id }));
