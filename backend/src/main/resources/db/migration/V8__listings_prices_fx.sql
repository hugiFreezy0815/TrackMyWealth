-- =============================================================================================
-- V8: Listings, prices and FX rates
-- =============================================================================================
-- Section 20 / DM-26/DM-27, RULE-014/026, FR-LST-*, FR-PRC-*: Instrument and Listing are
-- separate entities (one ISIN, several exchange listings in different currencies); prices attach
-- to the Listing, never to the Instrument directly. Raw prices are immutable; adjusted series
-- are derived on read from separately stored corporate actions (V9).
-- =============================================================================================

CREATE TABLE listing (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    security_id         UUID NOT NULL REFERENCES security(id),
    mic                    CHAR(4),          -- ISO 10383 Market Identifier Code
    ticker                   TEXT,
    trading_currency           CHAR(3) NOT NULL,
    is_primary_listing           BOOLEAN NOT NULL DEFAULT FALSE, -- FR-LST-003
    trading_calendar_code          TEXT,     -- references a reference-data trading calendar (V18)
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_listing_security ON listing(security_id);
CREATE INDEX idx_listing_ticker ON listing(ticker);
CREATE UNIQUE INDEX uq_listing_one_primary_per_security ON listing(security_id) WHERE is_primary_listing;

COMMENT ON TABLE listing IS
    'FR-LST-005: trading calendar + timezone are required per listing so a non-trading day can be distinguished from a genuine data gap (prerequisite for the daily valuation series behind TWR, FR-PERF-010).';

-- FR-PRC-009/RULE-026: prices are stored exactly as delivered. Split/dividend-adjusted series are
-- computed on read from listing prices + security corporate_action rows (V9) - never stored here.
-- Note: PostgreSQL requires every unique/primary key on a partitioned table to include the
-- partition key column, so `id` cannot be a stand-alone PRIMARY KEY here - it is combined with
-- price_date into a composite primary key instead. `id` remains globally unique on its own
-- (generated via gen_random_uuid()) so application code can still treat it as a normal surrogate
-- key; the composite key is a partitioning artifact, not a modelling one.
CREATE TABLE price (
    id                UUID NOT NULL DEFAULT gen_random_uuid(),
    listing_id           UUID NOT NULL REFERENCES listing(id),
    price_date              DATE NOT NULL,
    price                     NUMERIC(20,6) NOT NULL,
    currency                    CHAR(3) NOT NULL,
    price_type                    TEXT NOT NULL DEFAULT 'CLOSE' CHECK (price_type IN ('CLOSE', 'NAV', 'MANUAL', 'INTRADAY')),
    source                          TEXT NOT NULL,
    retrieved_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, price_date),
    UNIQUE (listing_id, price_date, source)
) PARTITION BY RANGE (price_date);

-- NFR-TEC-003/DB-02: price is volume-dominant and time-ordered - partitioned by year. The
-- migration ships a fixed initial range; V91__extend_price_partitions.sql (a template a job or
-- an ops runbook re-applies periodically) adds future years. See docs/architecture/database-schema.md
-- for the partition-maintenance approach.
CREATE TABLE price_y2023 PARTITION OF price FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE price_y2024 PARTITION OF price FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE price_y2025 PARTITION OF price FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE price_y2026 PARTITION OF price FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE price_y2027 PARTITION OF price FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE price_default PARTITION OF price DEFAULT;

CREATE INDEX idx_price_listing_date ON price(listing_id, price_date DESC);

-- FR-PRC-013: FX rates are a parallel time series under the same provenance/gap/staleness rules.
CREATE TABLE fx_rate (
    id                UUID NOT NULL DEFAULT gen_random_uuid(),
    base_currency         CHAR(3) NOT NULL,
    quote_currency           CHAR(3) NOT NULL,
    rate_date                  DATE NOT NULL,
    rate                          NUMERIC(20,10) NOT NULL,
    source                          TEXT NOT NULL,
    retrieved_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, rate_date),
    UNIQUE (base_currency, quote_currency, rate_date, source)
) PARTITION BY RANGE (rate_date);

CREATE TABLE fx_rate_y2023 PARTITION OF fx_rate FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE fx_rate_y2024 PARTITION OF fx_rate FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE fx_rate_y2025 PARTITION OF fx_rate FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE fx_rate_y2026 PARTITION OF fx_rate FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE fx_rate_y2027 PARTITION OF fx_rate FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE fx_rate_default PARTITION OF fx_rate DEFAULT;

CREATE INDEX idx_fx_rate_pair_date ON fx_rate(base_currency, quote_currency, rate_date DESC);

COMMENT ON TABLE fx_rate IS
    'FR-CUR-010: conversion from an account currency to the user reporting currency uses the direct pair where available and never chains through an intermediate currency (avoids compounding rounding error).';
