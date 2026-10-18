-- =============================================================================================
-- V95: An account's contract number, besides its IBAN (US-07-20, #323)
-- =============================================================================================
-- An LBS building-savings statement names its account by a ten-digit contract number, not by an
-- IBAN. An account other than a credit card can register one besides its IBAN or masked card
-- number. As for those (V73), only an HMAC-SHA256 under a key of the workspace and a masked
-- display value (the last 4 digits) are stored; the plain number never is.
-- =============================================================================================

ALTER TABLE account
ADD COLUMN contract_identifier_hash VARCHAR(64),
ADD COLUMN contract_identifier_masked VARCHAR(64);

ALTER TABLE account
ADD CONSTRAINT chk_account_contract_identifier_hash CHECK (
    contract_identifier_hash IS NULL OR contract_identifier_hash ~ '^[0-9a-f]{64}$'
);

ALTER TABLE account
ADD CONSTRAINT chk_account_contract_identifier_masked CHECK (
    (contract_identifier_hash IS NULL) = (contract_identifier_masked IS NULL)
);

-- GlobalExceptionHandler answers a race on this index as ACCOUNT_IDENTIFIER_IN_USE.
CREATE UNIQUE INDEX uq_account_workspace_contract_identifier
ON account (workspace_id, contract_identifier_hash)
WHERE contract_identifier_hash IS NOT NULL;

-- A template's rule may read a contract number too.
ALTER TABLE import_template
DROP CONSTRAINT chk_import_template_account_identifier_rule;

ALTER TABLE import_template
ADD CONSTRAINT chk_import_template_account_identifier_rule CHECK (
    account_identifier_rule IS NULL
    OR (
        jsonb_typeof(account_identifier_rule) = 'object'
        AND account_identifier_rule ->> 'kind' IN ('IBAN', 'MASKED_CARD', 'CONTRACT_NUMBER')
    )
);
