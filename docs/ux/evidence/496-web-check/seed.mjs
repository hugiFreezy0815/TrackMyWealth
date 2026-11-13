// Seeds a fresh compose stack for the PR #562 (value history) web check with synthetic data only.
// Usage: TMW_API=http://localhost:8090 node seed.mjs <envFile>
import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const API = process.env.TMW_API ?? 'http://localhost:8090';
const [envFile] = process.argv.slice(2);

async function call(method, path, { body, token } = {}) {
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
  body: { ...admin, workspaceName: 'Synthetic household', currencyCode: 'EUR' },
});
await call('POST', '/api/v1/admin/users', {
  body: { ...member, role: 'STANDARD_USER', language: 'EN' },
  token: setup.accessToken,
});
const token = (await call('POST', '/api/v1/auth/login', { body: member })).tokens.accessToken;

const portfolio = (name, country, portfolioCurrency) =>
  call('POST', '/api/v1/portfolios', {
    token,
    body: { name, country, institutionType: 'BANK', portfolioCurrency },
  });
const account = (portfolioId, name, accountType, nativeCurrency) =>
  call('POST', '/api/v1/accounts', {
    token,
    body: { portfolioId, name, accountType, nativeCurrency },
  });
const book = (accountId, body) =>
  call('POST', `/api/v1/accounts/${accountId}/transactions`, { token, body });

const dkb = await portfolio('Synthetic Bank', 'DE', 'EUR');
const giro = await account(dkb.id, 'Girokonto', 'CASH', 'EUR');
const savings = await account(dkb.id, 'Tagesgeld', 'SAVINGS', 'EUR');
await call('POST', `/api/v1/accounts/${giro.id}/opening-balance`, {
  token,
  body: { date: '2026-01-01', balance: '5000.00', currency: 'EUR' },
});
await call('POST', `/api/v1/accounts/${savings.id}/opening-balance`, {
  token,
  body: { date: '2026-01-01', balance: '12000.00', currency: 'EUR' },
});

// Monthly salary + a few expenses, Jan–Nov 2026; weekly expenses in the last 5 weeks.
for (let month = 0; month < 11; month += 1) {
  const day = (d) => `2026-${String(month + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
  await book(giro.id, {
    transactionType: 'INCOME',
    bookingDate: day(1),
    amount: '4200.00',
    currency: 'EUR',
    merchantDescription: 'Synthetic employer',
  });
  await book(giro.id, {
    transactionType: 'EXPENSE',
    bookingDate: day(3),
    amount: '-1450.00',
    currency: 'EUR',
    merchantDescription: 'Rent',
  });
  await book(giro.id, {
    transactionType: 'EXPENSE',
    bookingDate: day(10),
    amount: `-${(600 + month * 37).toFixed(2)}`,
    currency: 'EUR',
    merchantDescription: 'Groceries',
  });
}
for (let index = 0; index < 10; index += 1) {
  const date = new Date(Date.UTC(2026, 10, 12) - index * 3 * 86_400_000).toISOString().slice(0, 10);
  await book(giro.id, {
    transactionType: 'EXPENSE',
    bookingDate: date,
    amount: `-${(20 + index * 13).toFixed(2)}`,
    currency: 'EUR',
    merchantDescription: 'Synthetic shop',
  });
}
await book(giro.id, {
  transactionType: 'TRANSFER',
  bookingDate: '2026-10-30',
  amount: '-500.00',
  currency: 'EUR',
  merchantDescription: 'Transfer to Tagesgeld',
  counterpartyAccountId: savings.id,
});

// A CHF portfolio in the EUR workspace: needs FX, shows the "≈" line.
const chf = await portfolio('Synthetic CH Bank', 'CH', 'CHF');
const chfCash = await account(chf.id, 'Privatkonto', 'CASH', 'CHF');
await call('POST', `/api/v1/accounts/${chfCash.id}/opening-balance`, {
  token,
  body: { date: '2026-06-01', balance: '3000.00', currency: 'CHF' },
});
await book(chfCash.id, {
  transactionType: 'INCOME',
  bookingDate: '2026-10-25',
  amount: '800.00',
  currency: 'CHF',
  merchantDescription: 'Synthetic refund',
});

for (const period of ['1W', '1M', 'YTD', '1Y', 'MAX']) {
  const h = await call('GET', `/api/v1/net-worth/history?period=${period}&currency=EUR`, { token });
  console.log(
    period,
    h.points.length,
    'pts',
    JSON.stringify(h.change),
    'complete',
    h.complete,
    h.warnings.length,
    'warnings',
  );
}
const list = await call('GET', '/api/v1/portfolios', { token });
console.log(JSON.stringify(list.map((p) => ({ name: p.name, change: p.change, status: p.valueStatus }))));
console.log(JSON.stringify({ dkb: dkb.id, chf: chf.id }));
