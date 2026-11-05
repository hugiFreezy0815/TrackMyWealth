package com.trackmywealth.backend.dto;

/**
 * Bounded result of re-running a card's settlement matching. Only proposals inserted by this run
 * are counted; a repeated idempotent run answers zero. Match history lives in the paged GET list.
 */
public record SettlementMatchRunResponse(int newlyProposed) {}
