package com.trackmywealth.backend.dto;

/** Result of {@code SecurityService#findOrCreate}: the record and whether this call created it. */
public record SecurityCreation(SecurityResponse security, boolean created) {}
