-- =============================================================================================
-- V6: Ownership and sharing
-- =============================================================================================
-- FR-HOU-002/003/006, FR-HHL-001: ownership is a dated relationship between a household member
-- and an account, never a column on the account itself - this is what lets FR-HOU-005 apply an
-- ownership share to person-scoped figures while a household-scoped figure counts a jointly
-- owned account exactly once, and lets a later household split (section 53) move an account
-- without rewriting history.
-- =============================================================================================

CREATE TABLE account_ownership (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id             UUID NOT NULL REFERENCES account(id),
    household_member_id     UUID NOT NULL REFERENCES household_member(id),
    ownership_share           NUMERIC(6,5) NOT NULL DEFAULT 1.00000 CHECK (ownership_share > 0 AND ownership_share <= 1),
    effective_from             DATE NOT NULL DEFAULT CURRENT_DATE, -- FR-HOU-006
    effective_to                DATE,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX idx_account_ownership_account ON account_ownership(account_id);
CREATE INDEX idx_account_ownership_member ON account_ownership(household_member_id);
-- At most one *currently effective* ownership row per (account, member) pair.
CREATE UNIQUE INDEX uq_account_ownership_current
    ON account_ownership(account_id, household_member_id) WHERE effective_to IS NULL;

COMMENT ON TABLE account_ownership IS
    'FR-HOU-005: person-scoped consolidated figures apply ownership_share; household-scoped figures count the full account value exactly once regardless of how many owners it has.';

-- FR-TEN-008: explicit, revocable, granular sharing between household members - never an implied
-- merge of another member's private data. Distinct from account_ownership (which expresses who
-- owns value) - this expresses who else may *view or edit* an account they do not own.
CREATE TABLE sharing_grant (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id              UUID NOT NULL REFERENCES household(id),
    granted_to_member_id        UUID NOT NULL REFERENCES household_member(id),
    scope_type                   TEXT NOT NULL CHECK (scope_type IN ('ACCOUNT', 'INSTITUTION', 'HOUSEHOLD')),
    scope_account_id              UUID REFERENCES account(id),
    scope_institution_id           UUID REFERENCES financial_institution(id),
    access_level                    TEXT NOT NULL CHECK (access_level IN ('NO_ACCESS', 'BALANCE_ONLY', 'READ', 'EDIT', 'FULL')), -- FR-HOU-004
    granted_by_member_id             UUID NOT NULL REFERENCES household_member(id),
    granted_at                        TIMESTAMPTZ NOT NULL DEFAULT now(), -- FR-HHL-005: grants are dated
    revoked_at                         TIMESTAMPTZ,
    CHECK (
        (scope_type = 'ACCOUNT' AND scope_account_id IS NOT NULL AND scope_institution_id IS NULL) OR
        (scope_type = 'INSTITUTION' AND scope_institution_id IS NOT NULL AND scope_account_id IS NULL) OR
        (scope_type = 'HOUSEHOLD' AND scope_account_id IS NULL AND scope_institution_id IS NULL)
    )
);
CREATE INDEX idx_sharing_grant_household ON sharing_grant(household_id);
CREATE INDEX idx_sharing_grant_member ON sharing_grant(granted_to_member_id);

COMMENT ON TABLE sharing_grant IS
    'FR-TEN-002/008/009: access is denied unless an explicit grant exists here. Revocation (revoked_at set) takes effect on the next request, not on token expiry (FR-TEN-009).';
