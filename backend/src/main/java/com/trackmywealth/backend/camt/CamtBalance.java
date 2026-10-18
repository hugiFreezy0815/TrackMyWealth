package com.trackmywealth.backend.camt;

/**
 * One {@code Bal} of a statement, as written.
 *
 * @param type {@code Tp/CdOrPrtry/Cd} ({@code OPBD}, {@code CLBD}, {@code ITBD}, ...), or {@code
 *     null} for a proprietary type
 */
public record CamtBalance(String type, CamtAmount amount, String creditDebit, CamtDate date) {}
