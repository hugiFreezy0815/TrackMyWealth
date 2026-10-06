package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Forces a {@code DUPLICATE} row into the commit, or keeps a {@code PARSED} row out of it
 * (US-07-04). An {@code ERROR} row can only stay excluded.
 */
public record ImportRowInclusionRequest(@NotNull Boolean included) {}
