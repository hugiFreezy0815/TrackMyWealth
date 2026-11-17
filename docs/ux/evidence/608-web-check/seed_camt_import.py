"""Adds the #597/#608 web-check data: a EUR expense with a rate on a CHF account, and a CAMT.053
import with an ISO bank transaction code. All values are synthetic."""
import json, sys, secrets, urllib.request, urllib.error
base, creds_path = sys.argv[1], sys.argv[2]
users = json.load(open(creds_path))["users"]
partner = users["partner"]
def call(method, path, body=None, token=None, version=None, raw=None, ctype="application/json"):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(base + "/api/v1" + path, data=data, method=method)
    if data is not None: req.add_header("Content-Type", ctype)
    if token: req.add_header("Authorization", "Bearer " + token)
    if version is not None: req.add_header("If-Match", f'"{version}"')
    try:
        with urllib.request.urlopen(req) as r:
            txt = r.read().decode(); return json.loads(txt) if txt else None
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} -> {e.code} {e.read().decode()[:400]}")
login = call("POST", "/auth/login", {"email": partner["email"], "password": partner["password"]})
tok = login["tokens"]["accessToken"]
def items(x):
    return x if isinstance(x, list) else x.get("content", x.get("items", []))
accounts = {}
for a in items(call("GET", "/accounts", token=tok)):
    accounts[a["name"]] = a

cash = accounts["Demo · Cloudberry Current"]
eur = accounts["Demo · Paper Moon Current"]
row = call("POST", f"/accounts/{cash['id']}/transactions", {
    "transactionType": "EXPENSE", "bookingDate": json.load(open(creds_path))["anchor"],
    "amount": "-84.35", "currency": "EUR", "fxRateToAccountCurrency": "0.951234",
    "merchantDescription": "Lakeside Bistro Konstanz", "externalId": "tmw-597-check-fx"}, token=tok)
print("fx row", row["id"], row["amount"], row["currency"], row["amountInAccountCurrency"], row["fxRateToAccountCurrency"])
day = json.load(open(creds_path))["anchor"]
xml = ('<?xml version="1.0" encoding="UTF-8"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">'
 '<BkToCstmrStmt><GrpHdr><MsgId>MSG-597</MsgId><CreDtTm>' + day + 'T06:00:00</CreDtTm></GrpHdr>'
 '<Stmt><Id>S-597</Id><Acct><Id><IBAN>DE89370400440532013000</IBAN></Id><Ccy>EUR</Ccy></Acct>'
 '<Ntry><NtryRef>E-1</NtryRef><Amt Ccy="EUR">23.40</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>'
 '<BookgDt><Dt>' + day + '</Dt></BookgDt><ValDt><Dt>' + day + '</Dt></ValDt><AcctSvcrRef>REF-597-1</AcctSvcrRef>'
 '<BkTxCd><Domn><Cd>PMNT</Cd><Fmly><Cd>CCRD</Cd><SubFmlyCd>POSD</SubFmlyCd></Fmly></Domn></BkTxCd>'
 '<NtryDtls><TxDtls><Refs><AcctSvcrRef>REF-597-1</AcctSvcrRef></Refs><Amt Ccy="EUR">23.40</Amt><CdtDbtInd>DBIT</CdtDbtInd>'
 '<RltdPties><Cdtr><Pty><Nm>Harbour Books GmbH</Nm></Pty></Cdtr></RltdPties>'
 '<RmtInf><Ustrd>Card payment Harbour Books</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry></Stmt></BkToCstmrStmt></Document>')
b = secrets.token_hex(16)
body = (f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="synthetic-597.camt053.xml"\r\n'
        f'Content-Type: application/xml\r\n\r\n{xml}\r\n--{b}--\r\n').encode()
batch = call("POST", f"/accounts/{eur['id']}/imports", token=tok, raw=body, ctype=f"multipart/form-data; boundary={b}")
print("batch", batch["status"], batch["counts"], batch.get("accountIdentification"))
path = f"/accounts/{eur['id']}/imports/{batch['id']}"
ident = batch.get("accountIdentification")
if ident and ident.get("confirmationRequired") and not ident.get("confirmed"):
    batch = call("POST", path + "/confirm-account", token=tok, version=batch["version"])
done = call("POST", path + "/commit", token=tok, version=batch["version"])
print("commit", done["status"], done["counts"])
