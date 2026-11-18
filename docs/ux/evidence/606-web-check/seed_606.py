"""PR #606 web check: CHF accounts inside the EUR demo portfolio. Synthetic values only."""
import json, sys, urllib.request, urllib.error, datetime
base, creds_path = sys.argv[1], sys.argv[2]
creds = json.load(open(creds_path)); partner = creds["users"]["partner"]
def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + "/api/v1" + path, data=data, method=method)
    if data is not None: req.add_header("Content-Type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            t = r.read().decode(); return json.loads(t) if t else None
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} -> {e.code} {e.read().decode()[:400]}")
tok = call("POST", "/auth/login", {"email": partner["email"], "password": partner["password"]})["tokens"]["accessToken"]
pf = next(p for p in call("GET", "/portfolios", token=tok) if p["name"] == "Demo · Paper Moon Bank")
day = str(datetime.date.fromisoformat(creds["anchor"]) - datetime.timedelta(days=30))
known = call("POST", "/accounts", {"name": "Demo · Glacier Franc Current", "portfolioId": pf["id"],
    "accountType": "CASH", "nativeCurrency": "CHF"}, tok)
call("POST", f"/accounts/{known['id']}/opening-balance", {"date": day, "balance": "-100.00", "currency": "CHF"}, tok)
call("POST", "/accounts", {"name": "Demo · Glacier Franc Savings", "portfolioId": pf["id"],
    "accountType": "SAVINGS", "nativeCurrency": "CHF"}, tok)
view = call("GET", f"/portfolios/{pf['id']}/view", token=tok)
print("portfolio", view.get("portfolioCurrency"), view.get("total"))
for s in view["sections"]:
    for a in s["accounts"]:
        print(s["section"], a["name"], a["currency"], a["balance"], a["valueInPortfolioCurrency"], a["valueKnown"], a["valueStatus"])
