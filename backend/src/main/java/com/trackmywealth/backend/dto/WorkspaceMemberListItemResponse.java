package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * A member visible to their own workspace, without global user profile or authentication details.
 *
 * @param userId the member's login; every member has exactly one (FR-USR-008, V110)
 * @param active true when the workspace membership is active (independent of login status)
 */
public record WorkspaceMemberListItemResponse(
    UUID id, UUID userId, String displayName, boolean active, int version) {}
