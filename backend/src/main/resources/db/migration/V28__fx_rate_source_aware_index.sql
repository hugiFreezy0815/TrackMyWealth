-- =============================================================================================
-- V28: fx_rate index covering the actual US-06-01 read pattern (source included)
-- =============================================================================================
-- V8's idx_fx_rate_pair_date covers (base_currency, quote_currency, rate_date DESC) only.
-- FxRateRepository's US-06-01 lookup query also filters on source (the "most recent rate on or
-- before a date, for a given pair, from a given source" contract fx_rate's own
-- UNIQUE(base_currency, quote_currency, rate_date, source) constraint anticipates more than one
-- of), so once a pair/date has rows from more than one source, that query has to filter extra
-- rows after the index seek instead of seeking straight to the right one. Adding rather than
-- replacing idx_fx_rate_pair_date: nothing today queries across all sources for a pair/date, but
-- nothing rules it out either, and V8 is already applied everywhere - fixed forward per this
-- project's migration convention rather than edited in place.
-- =============================================================================================

CREATE INDEX idx_fx_rate_pair_source_date ON fx_rate(base_currency, quote_currency, source, rate_date DESC);
