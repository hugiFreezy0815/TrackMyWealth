"""Adds N synthetic EXPENSE bookings to one account; all but KEEP_UNCAT get a category."""
import json, sys, time, urllib.request, urllib.error, datetime
from concurrent.futures import ThreadPoolExecutor
creds, api, account, n, keep = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
p = json.load(open(creds))['users']['partner']
def call(method, path, body=None, token=None, if_match=None, tries=5):
    h = {'Content-Type': 'application/json'}
    if token: h['Authorization'] = 'Bearer ' + token
    if if_match: h['If-Match'] = if_match
    for attempt in range(tries):
        try:
            r = urllib.request.urlopen(urllib.request.Request(api + path, method=method, headers=h,
                data=json.dumps(body).encode() if body is not None else None))
            raw = r.read()
            return (json.loads(raw) if raw else None), r.headers.get('ETag')
        except urllib.error.HTTPError as e:
            if e.code in (409, 422, 429, 503) and attempt < tries - 1:
                time.sleep(1 + attempt); continue
            sys.exit(f"{method} {path} {e.code} {e.read().decode()[:300]}")
tok = call('POST', '/api/v1/auth/login', {'email': p['email'], 'password': p['password']})[0]['tokens']['accessToken']
tx = f'/api/v1/accounts/{account}/transactions'
cur = 'CHF' if 'Cloudberry' in sys.argv[6:] else 'CHF'
merchants = ['Grocer', 'Bakery', 'Rail pass', 'Bookshop', 'Pharmacy', 'Streaming', 'Cafe']
def create(i):
    day = datetime.date(2026, 11, 10) - datetime.timedelta(days=i // 4)
    return call('POST', tx, {'transactionType': 'EXPENSE', 'bookingDate': day.isoformat(),
        'amount': f'-{7 + (i * 37) % 180}.{i % 100:02d}', 'currency': cur,
        'merchantDescription': f'Synthetic {merchants[i % 7]} {i:05d}'}, tok)[0]['id']
with ThreadPoolExecutor(8) as pool:
    ids = list(pool.map(create, range(n)))
cats = call('GET', '/api/v1/categories', token=tok)[0]
pick = next(c for c in cats if c['code'] != 'UNCATEGORIZED' and c.get('active', True) and c.get('parentId'))
def categorise(tid):
    _, etag = call('GET', f'{tx}/{tid}', token=tok)
    call('PUT', f'{tx}/{tid}/category', {'categoryId': pick['id']}, tok, etag)
with ThreadPoolExecutor(8) as pool:
    list(pool.map(categorise, ids[keep:]))
total = call('GET', f'{tx}?size=1', token=tok)[0]['page']['totalElements']
unc = call('GET', f'{tx}?size=1&uncategorized=true', token=tok)[0]['page']['totalElements']
print(account, 'total', total, 'uncategorised', unc)
