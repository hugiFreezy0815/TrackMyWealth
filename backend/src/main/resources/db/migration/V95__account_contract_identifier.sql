-- #323: contract identifiers coexist with an account's existing IBAN or masked card.
-- The plain contract number is never persisted. Every hash is workspace-keyed (HMAC-SHA256).
ALTER TABLE account
    ADD COLUMN contract_identifier_hash VARCHAR(64),
    ADD COLUMN contract_identifier_masked VARCHAR(64);

CREATE UNIQUE INDEX uq_account_workspace_contract_identifier
    ON account (workspace_id, contract_identifier_hash)
    WHERE contract_identifier_hash IS NOT NULL;
