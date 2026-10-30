package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * One category as the calling workspace sees it (US-08-04): labels and {@code active} are the
 * effective values, i.e. a shipped default's with this workspace's override applied, and {@code
 * active} is false whenever the category or any of its ancestors is inactive. {@code level} is 1
 * for a top-level category, up to 3. {@code customised} marks a default this workspace has
 * relabelled or deactivated; {@code protectedCategory} marks one the product relies on
 * (UNCATEGORIZED, TRANSFER_INTERNAL), which can never be deactivated, deleted or given children.
 *
 * <p>{@code version} is the concurrency token to send as a strong {@code If-Match} ETag on every
 * state-changing request for this category (ADR 0004).
 */
public record CategoryResponse(
    UUID id,
    UUID parentId,
    String code,
    String nameEn,
    String nameDe,
    int level,
    boolean active,
    boolean systemDefault,
    boolean protectedCategory,
    boolean customised,
    int version) {}
