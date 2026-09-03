# EPIC 02 — User Administration & Authorization

Covers `app_user`, `refresh_token`, `user_session` (V2, V16) and the service-layer rules that sit
above them. FR-USR-*, FR-AUT-* (section 8, 49).

---

## US-02-01 — Administrator creates, edits, disables and reactivates users

**Actor:** System Administrator
**Objective:** FR-USR-003.
**Story:** As a System Administrator, I want to create, edit, disable and reactivate user
accounts, so that I can manage who can authenticate to this deployment.
**Preconditions:** Caller is authenticated with `role = SYSTEM_ADMINISTRATOR`.
**Acceptance criteria:**
- Given valid email/role/language, when an admin creates a user, then an `app_user` row is
  created with `status = 'ACTIVE'`, `password_hash` set via a memory-hard hash (FR-AUT-007), and
  an `admin_audit_log` row is written.
- Given an active administrator disables another user, when the request is processed, then that
  user's `status` becomes `DISABLED`, all of their `refresh_token` rows are marked revoked, and
  `app_user.token_version` is incremented (invalidating any still-live access token, FR-AUT-005).
- Given the target of disable/delete/demote is the **last active** `SYSTEM_ADMINISTRATOR`, when
  the request is processed, then it is rejected with a clear error (FR-USR-005 — enforced in the
  service layer via a `SELECT count(*) ... FOR UPDATE` over active admins, not a DB constraint;
  see the comment on `system_user`/`app_user` in `V2`).
**Applicable business rules:** FR-USR-003/004/005/009/010, FR-AUT-005/007.
**Data requirements:** Email unique (case-insensitive — `citext`), role in
`{SYSTEM_ADMINISTRATOR, STANDARD_USER}`.
**Error/edge cases:** Disabling yourself as the sole admin; re-enabling a disabled user (must
reset `failed_login_count`/`locked_until`).
**Authorization/privacy:** FR-TEN-007 — this endpoint touches administration rights only; it must
never be usable to read or grant financial-data access. FR-USR-011 — disabling a user must never
delete or orphan the linked `household_member`'s financial data.
**Dependencies:** US-01-03.
**Priority:** MUST.
**Definition of Done:** Unit + integration tests for last-admin protection and for token
invalidation on disable.
**Data-quality behaviour:** N/A.

---

## US-02-02 — Login issues a short-lived access token and a rotating refresh token

**Actor:** Any user
**Objective:** FR-AUT-003/004.
**Story:** As a user, I want to log in with email and password and receive a short-lived access
token plus a refresh mechanism, so that my session is both usable and revocable.
**Preconditions:** An active `app_user` exists.
**Acceptance criteria:**
- Given correct credentials, when login succeeds, then a JWT access token (TTL from
  `app.security.jwt.access-token-ttl-minutes`, default 15) is returned, a `refresh_token` row is
  created (`token_hash` stored, plaintext token returned once), and a `user_session` row is
  created with `status = 'ACTIVE'`.
- Given an incorrect password, when login is attempted 5 times within the rate-limit window, then
  further attempts are rejected with backoff and `app_user.locked_until` is set (FR-AUT-010).
- Given a valid refresh token, when it is used to obtain a new access token, then the old refresh
  token is marked `revoked_at`, a new one is issued in the same `family_id`, and reuse of the now-
  revoked token invalidates the entire family and sets `theft_suspected = true` on every token in
  it (FR-AUT-004).
**Applicable business rules:** FR-AUT-001..007/010, FR-CLI-006.
**Data requirements:** Password verified against `password_hash` (Argon2id); MFA TOTP checked
when `mfa_enabled = true` (FR-AUT-008).
**Error/edge cases:** Refresh-token-family-reuse ("theft") must revoke every token in the family
and force re-authentication, not just the one token.
**Authorization/privacy:** Web stores the refresh credential in an httpOnly/Secure/SameSite
cookie; mobile uses Keychain/Keystore — never `localStorage` (FR-AUT-006).
**Dependencies:** US-01-03.
**Priority:** MUST.
**Definition of Done:** Integration test covers login, refresh rotation, and the theft-detection
path (reusing a rotated-away token).
**Data-quality behaviour:** N/A.

---

## US-02-03 — Logout and session management

**Actor:** Any user
**Objective:** FR-AUT-002/005.
**Story:** As a user, I want to see my active sessions across devices and revoke any of them
individually, so that I can end access from a lost or old device.
**Preconditions:** Authenticated.
**Acceptance criteria:**
- Given a user with three active sessions, when they list sessions, then all three
  `user_session` rows (with device labels and last-seen times) are returned.
- Given a user revokes one session, when the revoke request completes, then that session's
  `status` becomes `REVOKED`, its `refresh_token` is revoked, and the next API call bearing that
  session's access token is rejected (checked via `app_user.token_version`/a per-session marker,
  not by waiting for JWT expiry — FR-AUT-005 is explicit that statelessness is not an acceptable
  justification for delayed revocation).
**Applicable business rules:** FR-AUT-002/005.
**Data requirements:** None beyond `user_session`.
**Error/edge cases:** Revoking the session currently making the request — must succeed and the
response must still return normally before the token becomes invalid for the *next* request.
**Authorization/privacy:** A user may only list/revoke their own sessions.
**Dependencies:** US-02-02.
**Priority:** MUST.
**Definition of Done:** Integration test revokes a session and asserts the next request with its
access token is `401`.
**Data-quality behaviour:** N/A.

---

## US-02-04 — TOTP multi-factor authentication

**Actor:** Any user
**Objective:** FR-AUT-008.
**Story:** As a user, I want to enable TOTP MFA on my account, so that a leaked password alone is
not sufficient to access my financial data.
**Preconditions:** Authenticated, recent re-authentication (FR-AUT-012).
**Acceptance criteria:**
- Given MFA is not yet enabled, when the user starts enrollment, then a TOTP secret is generated,
  stored encrypted in `app_user.mfa_totp_secret`, and a QR/setup code is returned; `mfa_enabled`
  stays `false` until the user confirms one valid code.
- Given MFA is enabled, when the user logs in with a correct password, then a second step
  requiring a valid TOTP code is required before a token is issued.
- Given the deployment policy makes MFA mandatory (OPEN-025, a per-deployment configuration
  value), when a user without MFA attempts to log in, then they are required to enroll before
  proceeding.
**Applicable business rules:** FR-AUT-008/012.
**Data requirements:** TOTP secret encrypted at rest (NFR-SEC-001).
**Error/edge cases:** Clock skew tolerance for TOTP validation; lost-device recovery (out of MVP
scope — document as a known gap, do not silently disable MFA).
**Authorization/privacy:** Enrollment/disable requires recent re-authentication (FR-AUT-012).
**Dependencies:** US-02-02.
**Priority:** MUST.
**Definition of Done:** Integration test for enrollment, login with MFA, and rejection of a stale
TOTP code.
**Data-quality behaviour:** N/A.

---

## US-02-05 — Administration rights never confer financial-data access

**Actor:** System Administrator who is also a household member (common in a self-hosted,
single-household deployment)
**Objective:** FR-TEN-007 — a hard separation between the two permission domains.
**Story:** As a System Administrator who is also a household member, I want my administrative
role to grant me zero implicit access to any household's financial data — including my own —
beyond what my household membership already grants, so that the separation holds even when the
same person occupies both roles.
**Preconditions:** A `SYSTEM_ADMINISTRATOR` user linked to a `household_member`.
**Acceptance criteria:**
- Given a `SYSTEM_ADMINISTRATOR` with no `household_member` link, when they call any
  financial-data endpoint, then every request is denied (no household context resolves for them
  at all).
- Given a `SYSTEM_ADMINISTRATOR` linked to household A, when they attempt to read household B's
  accounts, then the request is denied identically to a `STANDARD_USER` attempting the same
  (FR-TEN-004/006 — indistinguishable denial).
- This is asserted by the FR-TEN-010 automated cross-tenant test suite (EPIC 28), which must
  include an administrator principal as one of its attack personas, not only standard users.
**Applicable business rules:** FR-TEN-004/006/007.
**Data requirements:** None.
**Error/edge cases:** None beyond the cross-tenant suite's standard matrix.
**Authorization/privacy:** This story *is* an authorization requirement.
**Dependencies:** EPIC 28.
**Priority:** MUST.
**Definition of Done:** Cross-tenant test suite includes and passes this case.
**Data-quality behaviour:** N/A.
