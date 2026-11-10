// Seeds a fresh compose stack for the #494 web check (S3 account detail) with synthetic data only.
// Usage: TMW_API=http://localhost:8090 node seed.mjs <envFile> <fixture.csv>
// <fixture.csv> is the committed synthetic DKB statement
// (backend/src/test/resources/import/dkb-girokonto-synthetic.csv). Writes the member's generated
// credentials to <envFile> and prints the ids the check needs.
import { randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const API = process.env.TMW_API ?? 'http://localhost:8090';
const [envFile, fixture] = process.argv.slice(2);

async function call(method, path, { body, token, ifMatch, form } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: {
      ...(form ? {} : { 'content-type': 'application/json' }),
      ...(token ? { authorization: 'Bearer ' + token } : {}),
      ...(ifMatch ? { 'if-match': ifMatch } : {}),
    },
    body: form ?? (body === undefined ? undefined : JSON.stringify(body)),
  });
  const json = await res.json().catch(() => null);
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${JSON.stringify(json)}`);
  return { json, etag: res.headers.get('etag') };
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
  token: setup.json.accessToken,
});
const token = (await call('POST', '/api/v1/auth/login', { body: member })).json.tokens.accessToken;
const as = (options = {}) => ({ token, ...options });

const portfolio = (
  await call(
    'POST',
    '/api/v1/portfolios',
    as({
      body: { name: 'DKB', country: 'DE', institutionType: 'BANK', portfolioCurrency: 'EUR' },
    }),
  )
).json;
const createAccount = async (name, accountType) =>
  (
    await call(
      'POST',
      '/api/v1/accounts',
      as({ body: { portfolioId: portfolio.id, name, accountType, nativeCurrency: 'EUR' } }),
    )
  ).json;
const giro = await createAccount('Girokonto', 'CASH');
const savings = await createAccount('Tagesgeld', 'SAVINGS');
const txPath = `/api/v1/accounts/${giro.id}/transactions`;

await call(
  'POST',
  `/api/v1/accounts/${giro.id}/opening-balance`,
  as({ body: { date: '2026-01-01', balance: '5000.00', currency: 'EUR' } }),
);

// 60 hand-entered bookings, one or two a day, newest 31 Oct 2026.
const merchants = ['Migros', 'Coop', 'Amazon.de', 'SBB', 'Spotify', 'Apotheke', 'Bäckerei Muster'];
const created = [];
for (let index = 0; index < 60; index += 1) {
  const day = new Date(Date.UTC(2026, 9, 31) - Math.floor(index / 2) * 86_400_000);
  const income = index % 13 === 5;
  created.push(
    (
      await call(
        'POST',
        txPath,
        as({
          body: {
            transactionType: income ? 'INCOME' : 'EXPENSE',
            bookingDate: day.toISOString().slice(0, 10),
            amount: income ? '250.00' : `-${(7 + ((index * 37) % 180)).toFixed(2)}`,
            currency: 'EUR',
            merchantDescription: income ? 'Synthetic refund' : merchants[index % merchants.length],
          },
        }),
      )
    ).json,
  );
}

// Categorise all but four, so the "Uncategorised" chip and banner show.
const categories = (await call('GET', '/api/v1/categories', as())).json;
const pick = categories.find((category) => category.code !== 'UNCATEGORIZED' && category.active);
for (const transaction of created.slice(4)) {
  const current = await call('GET', `${txPath}/${transaction.id}`, as());
  await call(
    'PUT',
    `${txPath}/${transaction.id}/category`,
    as({ body: { categoryId: pick.id }, ifMatch: current.etag }),
  );
}

// An internal transfer to the savings account.
await call(
  'POST',
  txPath,
  as({
    body: {
      transactionType: 'TRANSFER',
      bookingDate: '2026-10-30',
      amount: '-500.00',
      currency: 'EUR',
      merchantDescription: 'Transfer to Tagesgeld',
      counterpartyAccountId: savings.id,
    },
  }),
);

// Import the synthetic statement, then void one imported booking (it gets a reversal row).
const form = new FormData();
form.append(
  'file',
  new Blob([readFileSync(fixture)], { type: 'text/csv' }),
  'dkb-girokonto-synthetic.csv',
);
let batch = await call('POST', `/api/v1/accounts/${giro.id}/imports`, as({ form }));
const batchPath = `/api/v1/accounts/${giro.id}/imports/${batch.json.id}`;
batch = await call('POST', `${batchPath}/confirm-account`, as({ ifMatch: batch.etag }));
await call('POST', `${batchPath}/commit`, as({ ifMatch: batch.etag }));
const page = (await call('GET', `${txPath}?size=200`, as())).json;
const imported = page.content.find((row) => row.importBatch && row.amount.startsWith('-'));
const current = await call('GET', `${txPath}/${imported.id}`, as());
await call(
  'DELETE',
  `${txPath}/${imported.id}?reason=${encodeURIComponent('Synthetic duplicate')}`,
  as({ ifMatch: current.etag }),
);

// A statement balance that does not match the ledger: an open reconciliation difference.
await call(
  'POST',
  `/api/v1/accounts/${giro.id}/snapshots`,
  as({ body: { snapshotDate: '2026-10-31', balance: '1234.56' } }),
);

const summary = (await call('GET', `/api/v1/accounts/${giro.id}`, as())).json;
console.log(
  JSON.stringify({
    account: giro.id,
    transactions: (await call('GET', `${txPath}?size=1`, as())).json.page.totalElements,
    reconciliation: summary.reconciliation.status,
  }),
);
