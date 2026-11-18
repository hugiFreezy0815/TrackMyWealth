"""Adds N new bookings with distinct names (so no learned rule categorises them) to an account."""
import json, secrets, sys, urllib.request
creds, api, account, n, tag = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5]
p = json.load(open(creds))['users']['partner']
def call(method, path, body=None, token=None):
    h = {'Content-Type': 'application/json'}
    if token: h['Authorization'] = 'Bearer ' + token
    r = urllib.request.urlopen(urllib.request.Request(api + path, method=method, headers=h,
        data=json.dumps(body).encode() if body is not None else None))
    raw = r.read()
    return json.loads(raw) if raw else None
tok = call('POST', '/api/v1/auth/login', {'email': p['email'], 'password': p['password']})['tokens']['accessToken']
tx = f'/api/v1/accounts/{account}/transactions'
for i in range(n):
    call('POST', tx, {'transactionType': 'EXPENSE', 'bookingDate': '2026-11-12', 'amount': f'-{11 + i}.50',
        'currency': 'CHF', 'merchantDescription': f'{tag}{secrets.token_hex(3)} {secrets.token_hex(2)}'}, tok)
print('uncategorised', call('GET', f'{tx}?size=1&uncategorized=true', token=tok)['page']['totalElements'],
      'total', call('GET', f'{tx}?size=1', token=tok)['page']['totalElements'])
