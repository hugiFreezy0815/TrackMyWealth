package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of a batched date lookup (#445): the id it is grouped by - an account's, as the query
 * documents - and the date.
 */
public record KeyedDate(UUID key, LocalDate date) {}
