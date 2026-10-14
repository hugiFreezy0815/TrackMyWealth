-- US-07-10: an import file names its account. An account's identifier (IBAN, or a credit card's
-- masked number) is stored only as an HMAC-SHA256 under a key of its workspace, plus a masked
-- display value; the plain number never is.

ALTER TABLE account
ADD COLUMN identifier_hash VARCHAR(64);

ALTER TABLE account
ADD CONSTRAINT chk_account_identifier_hash CHECK (
    identifier_hash IS NULL OR identifier_hash ~ '^[0-9a-f]{64}$'
);

CREATE UNIQUE INDEX uq_account_workspace_identifier_hash
ON account (workspace_id, identifier_hash)
WHERE identifier_hash IS NOT NULL;

-- Until now identifier_masked was free text the account update stored as sent (never shown), so it
-- may hold a full account number. From now on only the identifier registration writes it, together
-- with the hash.
UPDATE account SET identifier_masked = NULL
WHERE identifier_masked IS NOT NULL;

ALTER TABLE account
ADD CONSTRAINT chk_account_identifier_masked CHECK (
    (identifier_hash IS NULL) = (identifier_masked IS NULL)
);

-- The template's rule for where a file names its account (PREAMBLE_LINE or COLUMN), versioned
-- with the template like every parse-relevant setting.
ALTER TABLE import_template
ADD COLUMN account_identifier_rule JSONB;

ALTER TABLE import_template
ADD CONSTRAINT chk_import_template_account_identifier_rule CHECK (
    account_identifier_rule IS NULL OR jsonb_typeof(account_identifier_rule) = 'object'
);

-- What the parsed file says about its account, and whether the member confirmed importing it into
-- the batch's account anyway. NULL: the template does not identify accounts.
ALTER TABLE import_batch
ADD COLUMN account_identification VARCHAR(20),
ADD COLUMN proposed_account_id UUID REFERENCES account (id) ON DELETE SET NULL,
ADD COLUMN account_confirmation_required BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN account_confirmed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE import_batch
ADD CONSTRAINT chk_import_batch_account_identification CHECK (
    account_identification IS NULL
    OR account_identification IN (
        'MATCHED',
        'MATCHED_MASKED',
        'OTHER_ACCOUNT',
        'NO_MATCH',
        'NOT_FOUND',
        'UNAVAILABLE'
    )
);
