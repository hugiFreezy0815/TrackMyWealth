package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A transfer detection to re-run once FX rates cover {@code bookingDate} (V54, #223). Internal to
 * {@code TransferRecheckService}; it lives in {@code dto} for the same ArchUnit reason as {@link
 * ResolvedFxRate}.
 */
public record PendingTransferDetection(
    UUID id, UUID workspaceId, LocalDate bookingDate, int rechecks) {}
