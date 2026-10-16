-- #323: contract identifiers coexist with an account's existing IBAN or masked card.
-- The plain contract number is never persisted. Every hash is workspace-keyed (HMAC-SHA256).
ALTER TABLE account
    ADD COLUMN contract_identifier_hash VARCHAR(64),
    ADD COLUMN contract_identifier_masked VARCHAR(64);

CREATE UNIQUE INDEX uq_account_workspace_contract_identifier
    ON account (workspace_id, contract_identifier_hash)
    WHERE contract_identifier_hash IS NOT NULL;

-- Extend the template rule's kind check while retaining existing IBAN and masked-card templates.
ALTER TABLE import_template DROP CONSTRAINT chk_import_template_account_identifier_rule;
ALTER TABLE import_template
    ADD CONSTRAINT chk_import_template_account_identifier_rule CHECK (
        account_identifier_rule IS NULL
        OR (
            jsonb_typeof(account_identifier_rule) = 'object'
            AND account_identifier_rule ->> 'kind' IN ('IBAN', 'MASKED_CARD', 'CONTRACT_NUMBER')
        )
    );

ALTER TABLE account
    ADD CONSTRAINT chk_account_contract_identifier_hash CHECK (
        contract_identifier_hash IS NULL
        OR contract_identifier_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT chk_account_contract_identifier_masked CHECK (
        (contract_identifier_hash IS NULL) = (contract_identifier_masked IS NULL)
    );
