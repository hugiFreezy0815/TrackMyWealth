-- =============================================================================================
-- V42: Index for workspace-wide ledger scans by booking date (US-10-01)
-- =============================================================================================
-- TransferDetectionService matches transfer legs across all of a workspace's accounts within a
-- booking-date window, after every write. idx_transaction_workspace (V10) only narrows to the
-- workspace; this lets the scan narrow to the window as well.
-- =============================================================================================

CREATE INDEX idx_transaction_workspace_date ON transaction (workspace_id, booking_date);
