package com.trackmywealth.backend.dto;

/**
 * US-07-22 (#325): two rows of one import, by their position in the rows recorded, that the bank
 * booked as the legs of one currency exchange: {@code debit} sends the money, {@code credit}
 * receives it.
 */
public record ImportRowPair(int debit, int credit) {}
