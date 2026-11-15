# #536 web security headers: deployment check

Date: 2026-11-15. `main` at `db1df59a` plus this branch's Compose fix (`CORS_ALLOWED_ORIGINS`).

**Setup.** No public deployment of TrackMyWealth exists yet, so the check runs on a
production-shaped local deployment:

- `docker compose --profile web up -d --build`, with the README's reverse-proxy layout.
- Caddy 2.8 terminates HTTPS in front of it (`Caddyfile`, `compose.override.yml`). It serves the
  web app on `https://localhost` and the API on its own origin, `https://localhost:8443`, and
  redirects HTTP to HTTPS.
- Synthetic data from `scripts/seed_demo.py`, signed in as the demo partner.
- The certificate comes from Caddy's local CA; the browsers run with `ignoreHTTPSErrors`.

## Header scan

| Check | Result |
|---|---|
| `mobile/deploy/nginx/check-headers.sh` over HTTPS through the proxy (page, every dynamic route, 404s, hashed JS/CSS/assets, gzip) | Pass, exit 0 (`check-headers.log`) |
| Headers after the proxy (`https-headers.txt`) | All present: `no-store` on HTML, CSP, HSTS, `no-referrer`, `nosniff`, `DENY`, Permissions-Policy; HTTP → 308 to HTTPS |
| Mozilla HTTP Observatory 1.7.2 (`mdn-http-observatory-scan`, the scanner behind the website) | C+ (60), 10 of 12 tests pass (`observatory.json`) |

The two Observatory failures are `redirection-invalid-cert` and `hsts-invalid-cert`: the scanner
does not trust Caddy's local CA. Every header test passes, including
`csp-implemented-with-no-unsafe`, `referrer-policy-private` and
`x-frame-options-implemented-via-csp`. With a publicly trusted certificate the two -20 penalties
fall away, giving 100 plus the +15 bonus of those three tests: an expected A+. That figure is
derived from the scoring rules, not scanned; scan the real host once one exists.

The scanner ran inside the Compose network under the alias `app.trackmywealth.test`: it refuses
`localhost`, and this network's DNS blocks public names that resolve to 127.0.0.1.

## Browser checks (`deployed-536.mjs`)

| Check | Chrome 157 | WebKit 18.2 (Safari stand-in) |
|---|---|---|
| HTML over HTTPS carries `Cache-Control: no-store` | Pass | Pass |
| Sign-in works from the HTTPS web origin against the HTTPS API (CORS + CSP `connect-src`) | Pass | Pass |
| No `Referer` on API requests | Pass | Pass |
| No `Referer` to another site from an account URL | Pass | Pass |
| Back after leaving the site: sign-in form, no workspace data (PO decision 2026-11-14) | Pass | Pass |
| Sign-out lands on the sign-in form | Pass | Pass |
| After sign-out, Back six times never shows workspace data | Pass | Pass |
| No CSP violation | Pass | Pass |
| Another origin cannot frame the app | Pass | Pass |

Raw results: `results-chrome.json`, `results-webkit.json`. Screenshots: `*-1-account.png`
(signed in), `*-2-back-after-leaving.png`, `*-3-back-after-sign-out.png`.

WebKit keeps the blocked frame's URL but leaves its document empty and logs "Refused to load https://localhost/ because it does not appear in the frame-ancestors directive".

## Real Safari (`safari-536.mjs`, 2026-11-15)

Safari 27.2 was driven through `safaridriver` after `sudo safaridriver --enable`. `safaridriver`
cannot accept Caddy's untrusted local CA, so this run used the same Compose stack (this
branch's `main`) without the proxy. The web app was on `http://localhost:8084` and the API on
`http://localhost:8091`, with the same nginx headers. Browsers treat `localhost` as a secure
context. HTTPS itself is covered by the Chrome and WebKit runs above.

| Check | Safari 27.2 |
|---|---|
| Sign-in works (device locale German, member language English) | Pass |
| Back after leaving the site (to example.com): sign-in form, or the same member still signed in | Pass: Safari restored the page from its back-forward cache despite `no-store`, with the session intact |
| Sign-out lands on the sign-in form | Pass |
| After sign-out, Back six times never shows workspace data | Pass |

Unlike Chrome, Safari restores a `no-store` page after the member left the site. The #571
guard then keeps the session, because no sign-out happened since that page started. That is
the member's own session, not another member's data, and matches the PO decision of
2026-11-14 ("Back may ask for sign-in; it never shows the previous member's data"). After a
sign-out Safari shows only the sign-in form. Raw results: `results-safari.json`. Screenshots:
`safari-1-account.png`, `safari-2-back-after-leaving.png`, `safari-3-back-after-sign-out.png`.

## Found and fixed

Before the fix, the Compose stack could not sign in from an HTTPS web origin. The browser's
preflight from `https://localhost` to the API got **403**, because `docker-compose.yml` did not
pass `CORS_ALLOWED_ORIGINS` to the backend. The backend therefore only allowed the dev default
`http://localhost:*`. This branch forwards it, defaulting to the same dev value. Backend CI's
Compose check covers it, and `.env.example` and both READMEs document it.
