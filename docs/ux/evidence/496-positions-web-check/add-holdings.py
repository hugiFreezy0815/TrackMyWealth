"""Give the demo depot a later snapshot with six holdings (PR #573 browser check).

Usage: python3 add-holdings.py <demo-credentials.json> [API base URL, default http://127.0.0.1:8080]
Run once after scripts/seed_demo.py; it signs in as the demo partner (no MFA). A rerun of
seed_demo.py then fails its snapshot check, as intended for a disposable stack.
"""
import json, sys
from datetime import date
from urllib.request import Request, urlopen

BASE = (sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8080").rstrip("/") + "/api/v1"
creds = json.load(open(sys.argv[1]))["users"]["partner"]

def call(method, path, body=None, token=None):
    req = Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                  headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})})
    with urlopen(req) as res:
        raw = res.read()
        return json.loads(raw) if raw else None

login = call("POST", "/auth/login", {"email": creds["email"], "password": creds["password"]})
assert not login["mfaRequired"], "partner unexpectedly needs MFA"
tok = login["tokens"]["accessToken"]
accounts = call("GET", "/accounts", token=tok)
rows = accounts["content"] if isinstance(accounts, dict) else accounts
depot = next(a for a in rows if a["name"] == "Demo · Alpine Lantern Depot")
snaps = call("GET", f"/accounts/{depot['id']}/snapshots", token=tok)
snaps = snaps["content"] if isinstance(snaps, dict) else snaps
holdings = list(snaps[0]["holdings"])
for i, (name, kind, cls) in enumerate([
        ("Cloudberry Demo Bond Fund", "ETF", "FIXED_INCOME"),
        ("Paper Moon Demo Tech Share", "EQUITY", "EQUITY"),
        ("Alpine Demo Real Estate Fund", "FUND", "REAL_ESTATE"),
        ("Lantern Demo Gold Tracker", "ETF", "COMMODITY"),
        ("Cloudberry Demo Dividend Share", "EQUITY", "EQUITY")]):
    sec = call("POST", "/securities", {"idempotencyKey": f"pr573-check-{i}", "displayName": name,
               "denominationCurrency": "CHF", "instrumentType": kind, "assetClass": cls}, tok)
    holdings.append({"securityId": sec["id"], "quantity": str(10 + i * 5)})
call("POST", f"/accounts/{depot['id']}/snapshots",
     {"snapshotDate": str(date.today()), "balance": "48000.00", "holdings": holdings}, tok)
pos = call("GET", f"/accounts/{depot['id']}/positions", token=tok)
print("depot", depot["id"], "positions:", len(pos["positions"]))
