-- =============================================================================================
-- V46: Category label and system-default integrity (US-08-04 follow-up, #171)
-- =============================================================================================
-- The API already normalizes category labels and caps them at CategoryLabels.MAX_LENGTH (100),
-- but reference-data packages, imports and operational scripts also write these tables. Enforce
-- the same invariant at the database boundary so every writer sees the same contract.
--
-- PostgreSQL validates CHECK constraints against all existing rows when they are added. The V19
-- and V37 shipped defaults already satisfy the system-default relationship below, so migration
-- fails loudly instead of accepting historical inconsistency if an installation has drifted.
-- Keep the literal 100 in sync with CategoryLabels.MAX_LENGTH.
-- =============================================================================================

ALTER TABLE category
ADD CONSTRAINT category_label_length
CHECK (
    char_length(btrim(name_en)) BETWEEN 1 AND 100
    AND char_length(btrim(name_de)) BETWEEN 1 AND 100
),
ADD CONSTRAINT category_system_default_is_shared
CHECK ((workspace_id IS NULL) = is_system_default);

ALTER TABLE workspace_category_override
ADD CONSTRAINT workspace_category_override_label_length
CHECK (
    (name_en IS NULL OR char_length(btrim(name_en)) BETWEEN 1 AND 100)
    AND (name_de IS NULL OR char_length(btrim(name_de)) BETWEEN 1 AND 100)
);
