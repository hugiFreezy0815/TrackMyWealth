-- =============================================================================================
-- V56: Count unsuccessful re-detections of a pending transfer date (#223 review)
-- =============================================================================================
-- A date stays in transfer_detection_fx_pending (V54) while no FX rate lets detection judge its
-- cross-currency pair. For a currency the provider never publishes that is forever, and the FX job
-- re-ran detection there after every import that stored new rates. rechecks counts those runs;
-- after a few the job stops (TransferRecheckService.MAX_RECHECKS) and the date is judged again by
-- the next write nearby, as any other detection.
-- =============================================================================================

ALTER TABLE transfer_detection_fx_pending
ADD COLUMN rechecks SMALLINT NOT NULL DEFAULT 0;
