package com.trackmywealth.backend.camt;

/**
 * An {@code Amt} element as written: its unsigned decimal text and its {@code Ccy} attribute,
 * either {@code null} when absent. Not yet validated.
 */
public record CamtAmount(String value, String currency) {}
