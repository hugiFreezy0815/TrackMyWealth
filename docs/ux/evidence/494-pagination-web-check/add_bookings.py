import json, sys, urllib.request, urllib.error, datetime
creds, api, account = sys.argv[1], sys.argv[2], sys.argv[3]
p = json.load(open(creds))['users']['partner']
def call(method, path, body=None, token=None, if_match=None):
    h = {'Content-Type': 'application/json'}
    if token: h['Authorization'] = 'Bearer ' + token
    if if_match: h['If-Match'] = if_match
    try:
      r = urllib.request.urlopen(urllib.request.Request(api + path, method=method, headers=h,
        data=json.dumps(body).encode() if body is not None else None))
    except urllib.error.HTTPError as e:
      sys.exit(f"{method} {path} {e.code} {e.read().decode()}")
    raw = r.read()
    return (json.loads(raw) if raw else None), r.headers.get('ETag')
tok = call('POST', '/api/v1/auth/login', {'email': p['email'], 'password': p['password']})[0]['tokens']['accessToken']
tx = f'/api/v1/accounts/{account}/transactions'
merchants = ['Grocer', 'Bakery', 'Rail pass', 'Bookshop', 'Pharmacy', 'Streaming', 'Cafe']
ids = []
for i in range(70):
    day = datetime.date(2026, 11, 10) - datetime.timedelta(days=i // 2)
    body, _ = call('POST', tx, {'transactionType': 'EXPENSE', 'bookingDate': day.isoformat(),
        'amount': f'-{7 + (i * 37) % 180}.{i % 100:02d}', 'currency': 'CHF',
        'merchantDescription': f'Synthetic {merchants[i % 7]} {i:02d}'}, tok)
    ids.append(body['id'])
cats = call('GET', '/api/v1/categories', token=tok)[0]
pick = next(c for c in cats if c['code'] != 'UNCATEGORIZED' and c.get('active', True) and c.get('parentId'))
for tid in ids[12:]:
    _, etag = call('GET', f'{tx}/{tid}', token=tok)
    call('PUT', f'{tx}/{tid}/category', {'categoryId': pick['id']}, tok, etag)
total = call('GET', f'{tx}?size=1', token=tok)[0]['page']['totalElements']
unc = call('GET', f'{tx}?size=1&uncategorized=true', token=tok)[0]['page']['totalElements']
print('total', total, 'uncategorised', unc)
