// Seed for the #493 (S2 Portfolio View) web check. Synthetic data only, local stack.
// Usage: TMW_ADMIN_EMAIL=... TMW_ADMIN_PASSWORD=... TMW_EMAIL=... TMW_PASSWORD=... node seed.mjs
const API = 'http://localhost:8080/api/v1';
const env = process.env;

async function call(method, path, token, body, headers = {}) {
  const res = await fetch(API + path, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text.slice(0, 300)}`);
  return { json: text ? JSON.parse(text) : null, etag: res.headers.get('etag') };
}

const today = new Date().toISOString().slice(0, 10);

// Admin (EUR workspace, so every value is known on a stack without FX rates) and one member.
const setup = await call('POST', '/setup/administrator', null, {
  email: env.TMW_ADMIN_EMAIL,
  password: env.TMW_ADMIN_PASSWORD,
  workspaceName: 'Family Example',
  currencyCode: 'EUR',
});
const admin = setup.json.accessToken;
await call('POST', '/admin/users', admin, {
  email: env.TMW_EMAIL,
  password: env.TMW_PASSWORD,
  role: 'STANDARD_USER',
  language: 'EN',
});
const login = await call('POST', '/auth/login', null, {
  email: env.TMW_EMAIL,
  password: env.TMW_PASSWORD,
});
const t = login.json.tokens.accessToken;

const portfolio = async (body) => (await call('POST', '/portfolios', t, body)).json.id;
const account = async (body) => (await call('POST', '/accounts', t, body)).json.id;
const opening = (id, balance, currency = 'EUR') =>
  call('POST', `/accounts/${id}/opening-balance`, t, { date: today, balance, currency });

// "Bank Example" (EUR): current account, savings, credit card and a depot with 7 positions.
const bank = await portfolio({
  name: 'Bank Example',
  country: 'DE',
  institutionType: 'BANK',
  portfolioCurrency: 'EUR',
});
const giro = await account({ portfolioId: bank, name: 'Girokonto', accountType: 'CASH', nativeCurrency: 'EUR' });
await opening(giro, '4210.55');
const savings = await account({ portfolioId: bank, name: 'Tagesgeld', accountType: 'SAVINGS', nativeCurrency: 'EUR' });
await opening(savings, '15000.00');
const card = await account({ portfolioId: bank, name: 'Visa', accountType: 'CREDIT_CARD', nativeCurrency: 'EUR' });
await opening(card, '-1284.30');
const depot = await account({
  portfolioId: bank,
  name: 'Depot',
  accountType: 'SECURITIES',
  nativeCurrency: 'EUR',
  holdsPositions: true,
});
const securities = [];
for (const [i, name] of [
  'Example World Equity ETF',
  'Example Europe Bond Fund',
  'Example Emerging Markets ETF',
  'Example Global REIT',
  'Example Gold Tracker',
  'Example Small Cap ETF',
  'Example Money Market Fund',
].entries()) {
  const created = await call('POST', '/securities', t, {
    displayName: name,
    denominationCurrency: 'EUR',
    instrumentType: 'ETF',
    assetClass: 'EQUITY',
    idempotencyKey: `seed-545-${i}`,
  });
  securities.push(created.json.id);
}
await call('POST', `/accounts/${depot}/snapshots`, t, {
  snapshotDate: today,
  balance: '58930.20',
  holdings: securities.map((securityId, i) => ({
    securityId,
    quantity: ['310', '1250.5', '42', '18.75', '7', '120', '0.00125'][i],
    reportedCostBasis: ['27439.00', '11800.00', '1200.00', null, '900.00', '2000.00', null][i] ?? undefined,
    costBasisIsEstimated: i === 1,
  })),
});

// "Home" (EUR): a property worth less than its mortgage, so the total is negative (FR-NAV-15).
const home = await portfolio({ name: 'Home', portfolioCurrency: 'EUR' });
const house = await account({
  portfolioId: home,
  name: 'Flat',
  accountType: 'CUSTOM_ASSET',
  nativeCurrency: 'EUR',
  customAssetType: 'REAL_ESTATE',
});
await call('POST', `/accounts/${house}/valuations`, t, { valuationDate: today, value: '400000.00' });
const mortgage = await account({
  portfolioId: home,
  name: 'Mortgage',
  accountType: 'MORTGAGE',
  nativeCurrency: 'EUR',
  originalPrincipal: '450000.00',
  interestRatePercent: '1.85',
});

console.log(JSON.stringify({ bank, home, depot }));
