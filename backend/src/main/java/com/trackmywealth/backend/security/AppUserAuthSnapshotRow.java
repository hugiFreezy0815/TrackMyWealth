package com.trackmywealth.backend.security;

import java.util.UUID;

/**
 * One row of V81's {@code auth_user_snapshot(user, session)}, as Spring Data reads a native query
 * (#321); {@code AppUserRepository.findAuthSnapshot} turns it into an {@link AppUserAuthSnapshot}.
 * The getters follow the function's column names ({@code user_token_version} is {@code
 * getUserTokenVersion}).
 */
public interface AppUserAuthSnapshotRow {

  UUID getUserId();

  String getUserRole();

  String getUserStatus();

  Integer getUserTokenVersion();

  UUID getWorkspaceId();

  String getWorkspaceMemberStatus();

  String getSessionStatus();

  String getUserLanguage();
}
