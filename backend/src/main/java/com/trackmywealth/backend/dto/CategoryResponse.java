package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * One category as the calling workspace sees it (US-08-04): labels and {@code active} are the
 * effective values, i.e. a shipped default's with this workspace's override applied. {@code level}
 * is 1 for a top-level category, up to 3. {@code customised} marks a default this workspace has
 * relabelled or deactivated; {@code protectedCategory} marks one the product relies on
 * (UNCATEGORIZED, TRANSFER_INTERNAL), which can never be deactivated, deleted or given children.
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
    boolean customised) {}
