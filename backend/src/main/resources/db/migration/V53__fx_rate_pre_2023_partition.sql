-- =============================================================================================
-- V53: A partition for FX history before 2023 (#223)
-- =============================================================================================
-- V8 partitions fx_rate by year from 2023 on, plus a DEFAULT catch-all that database-schema.md
-- section 5 expects to stay empty. The FX import (US-06-04) loads history from the first
-- transaction booking on, which for a household's imported statements can be years earlier;
-- the ECB series starts in 1999. Without this partition all of that would land in DEFAULT.
--
-- One partition for everything before 2023 rather than one per year: it is reference data of at
-- most ~30 currencies a day (~200k rows for 1999-2022), small enough for a single table.
--
-- PostgreSQL refuses to attach a partition while DEFAULT holds rows in its range, so any such
-- rows (rates entered by hand) are moved out first and re-inserted through the parent, which
-- routes them into the new partition. The whole migration is one transaction.
-- =============================================================================================

CREATE TEMPORARY TABLE fx_rate_pre_2023_move ON COMMIT DROP AS
SELECT
    id,
    base_currency,
    quote_currency,
    rate_date,
    rate,
    source,
    retrieved_at
FROM fx_rate_default
WHERE rate_date < DATE '2023-01-01';

DELETE FROM fx_rate_default
WHERE rate_date < DATE '2023-01-01';

CREATE TABLE fx_rate_pre_2023 PARTITION OF fx_rate
FOR VALUES FROM (MINVALUE) TO ('2023-01-01');

INSERT INTO fx_rate (id, base_currency, quote_currency, rate_date, rate, source, retrieved_at)
SELECT
    id,
    base_currency,
    quote_currency,
    rate_date,
    rate,
    source,
    retrieved_at
FROM fx_rate_pre_2023_move;
