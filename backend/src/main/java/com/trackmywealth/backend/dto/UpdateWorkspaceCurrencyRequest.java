package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.NotBlank;

/** Replaces the workspace's default ISO 4217 display currency (US-06-05). */
public record UpdateWorkspaceCurrencyRequest(@NotBlank @ValidCurrencyCode String currency) {}
