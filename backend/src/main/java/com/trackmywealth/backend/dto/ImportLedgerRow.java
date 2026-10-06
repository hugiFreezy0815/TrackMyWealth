package com.trackmywealth.backend.dto;

/**
 * One import row the way the ledger records it (US-07-04): the {@code request} a member would send
 * for the same booking, and the {@code rawSourceData} JSON kept with the transaction.
 */
public record ImportLedgerRow(CreateTransactionRequest request, String rawSourceData) {}
