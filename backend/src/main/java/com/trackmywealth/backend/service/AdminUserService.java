package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.EditUserRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.entity.AdminAuditLog;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AdminAuditLogRepository;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.RefreshTokenRepository;
import com.trackmywealth.backend.repository.UserSessionRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * US-02-01: administrator-driven user lifecycle management. Edit/disable/reactivate deliberately
 * touch only {@code app_user}/{@code refresh_token}/{@code admin_audit_log} - never a
 * workspace-scoped table, per FR-TEN-007's separation between administration rights and
 * financial-data access.
 *
 * <p>{@link #createUser} is the one deliberate, narrow exception: every login-capable person needs
 * both an {@code app_user} row (authentication) and a {@code workspace_member} row (financial-data
 * ownership, RULE-018) - {@code SetupService} creates both together for the bootstrap
 * administrator, and this mirrors that for every user created afterward (#47). Without it, the new
 * user authenticates successfully but {@code app.current_workspace_id} never resolves for their
 * requests ({@code WorkspaceContextTransactionExecutionListener}), so every workspace-scoped table
 * denies them by default. This is not the FR-TEN-007 boundary US-02-05 protects: that story is
 * about the {@code role} column itself never granting implicit financial access beyond what
 * workspace membership already grants (an administrator who is also a member gets exactly a
 * member's access, no more) - it does not forbid an administrator from creating that membership in
 * the first place, which is the entire point of this endpoint existing.
 */
@Service
public class AdminUserService {

  private static final String SYSTEM_ADMINISTRATOR = "SYSTEM_ADMINISTRATOR";
  private static final String ACTIVE = "ACTIVE";

  // FR-USR-005: a workspace/deployment must always retain at least this many active
  // administrators - disabling/demoting one is only rejected when it would drop below this.
  private static final long MINIMUM_ACTIVE_ADMINISTRATORS = 1;

  private final AppUserRepository appUserRepository;
  private final WorkspaceRepository workspaceRepository;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final UserSessionRepository userSessionRepository;
  private final AdminAuditLogRepository adminAuditLogRepository;
  private final PasswordEncoder passwordEncoder;
  private final ObjectMapper objectMapper;
  private final VersionPreconditionService versionPreconditionService;

  public AdminUserService(
      AppUserRepository appUserRepository,
      WorkspaceRepository workspaceRepository,
      WorkspaceMemberRepository workspaceMemberRepository,
      RefreshTokenRepository refreshTokenRepository,
      UserSessionRepository userSessionRepository,
      AdminAuditLogRepository adminAuditLogRepository,
      PasswordEncoder passwordEncoder,
      ObjectMapper objectMapper,
      VersionPreconditionService versionPreconditionService) {
    this.appUserRepository = appUserRepository;
    this.workspaceRepository = workspaceRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.userSessionRepository = userSessionRepository;
    this.adminAuditLogRepository = adminAuditLogRepository;
    this.passwordEncoder = passwordEncoder;
    this.objectMapper = objectMapper;
    this.versionPreconditionService = versionPreconditionService;
  }

  @Transactional
  public UserSummaryResponse createUser(
      CreateUserRequest request, UUID actorUserId, UUID actorWorkspaceId) {
    assertEmailAvailable(request.email());

    if (actorWorkspaceId == null) {
      // Only reachable for an administrator created before this fix landed (or the pre-fix
      // bootstrap-only administrator's own account somehow losing its link) - no such state can
      // arise going forward, and no persistent deployment predates this fix.
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Acting administrator has no workspace of their own to link the new user to.");
    }
    // A reference, not a fetch: `workspace` itself is RLS-scoped by its own id (V20's
    // tenant_isolation_read policy), so a real SELECT here would need app.current_workspace_id
    // already set to this exact id to return anything - which JwtAuthenticationFilter already
    // established for actorWorkspaceId when it authenticated this same request, so a second
    // round trip to re-confirm it would be redundant, not more correct.
    Workspace workspace = workspaceRepository.getReferenceById(actorWorkspaceId);
    WorkspaceMember member =
        workspaceMemberRepository.save(WorkspaceMember.newLoginMember(workspace, request.email()));

    AppUser user = new AppUser();
    user.setEmail(request.email());
    user.setPasswordHash(passwordEncoder.encode(request.password()));
    user.setRole(request.role());
    user.setLanguage(request.language());
    user.setWorkspaceMember(member);
    user = saveOrRejectDuplicateEmail(user);

    writeAuditLog(actorUserId, "USER_CREATED", user.getId(), Map.of("role", request.role()));
    return toSummary(user);
  }

  @Transactional(readOnly = true)
  public UserSummaryResponse getUser(UUID targetUserId) {
    return toSummary(findUserOrThrow(targetUserId));
  }

  @Transactional
  public UserSummaryResponse editUser(
      UUID targetUserId, EditUserRequest request, Integer expectedVersion, UUID actorUserId) {
    AppUser target = findUserOrThrow(targetUserId);
    versionPreconditionService.requireCurrent(expectedVersion, target.getVersion(), "user");
    Map<String, Object> changes = new LinkedHashMap<>();

    if (request.email() != null) {
      if (request.email().isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email must not be blank.");
      }
      if (!request.email().equalsIgnoreCase(target.getEmail())) {
        assertEmailAvailable(request.email());
      }
      changes.put("email", request.email());
      target.setEmail(request.email());
    }
    if (request.language() != null) {
      changes.put("language", request.language());
      target.setLanguage(request.language());
    }
    if (request.role() != null && !request.role().equals(target.getRole())) {
      if (SYSTEM_ADMINISTRATOR.equals(target.getRole())) {
        // Demoting away from SYSTEM_ADMINISTRATOR - the same guard as disable (FR-USR-005).
        assertNotLastActiveAdministrator(target, "demote");
      }
      changes.put("role", Map.of("from", target.getRole(), "to", request.role()));
      target.setRole(request.role());
    }

    target = saveOrRejectDuplicateEmail(target);
    writeAuditLog(actorUserId, "USER_EDITED", targetUserId, changes);
    return toSummary(target);
  }

  @Transactional
  public UserSummaryResponse disableUser(
      UUID targetUserId, Integer expectedVersion, UUID actorUserId) {
    // Locks the target and every active administrator in one statement - see
    // AppUserRepository.lockTargetAndActiveAdministrators for why this must not be split into a
    // standalone target-row lock followed by a separate assertNotLastActiveAdministrator query
    // (#62's follow-up finding: two concurrent disableUser() calls on different active-admin
    // targets deadlocked doing exactly that).
    List<AppUser> lockedTargetAndAdministrators =
        appUserRepository.lockTargetAndActiveAdministrators(targetUserId);
    AppUser target = extractTargetOrThrow(lockedTargetAndAdministrators, targetUserId);
    versionPreconditionService.requireCurrent(expectedVersion, target.getVersion(), "user");
    long activeAdministratorCount = countActiveAdministrators(lockedTargetAndAdministrators);
    assertNotLastActiveAdministrator(target, activeAdministratorCount, "disable");

    target.setStatus("DISABLED");
    target.incrementTokenVersion();
    target = appUserRepository.save(target);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    refreshTokenRepository.revokeAllActiveTokensForUser(targetUserId, now);
    // user_session.status is a security-relevant signal now (US-02-03's per-session
    // JwtAuthenticationFilter check), not just a display field - without this, a disabled user's
    // sessions stayed ACTIVE in the DB (unusable in practice since AppUser.status is checked
    // first, but wrong for anything that trusts user_session.status directly).
    userSessionRepository.revokeAllActiveSessionsForUser(targetUserId, now);

    writeAuditLog(actorUserId, "USER_DISABLED", targetUserId, null);
    return toSummary(target);
  }

  @Transactional
  public UserSummaryResponse reactivateUser(
      UUID targetUserId, Integer expectedVersion, UUID actorUserId) {
    AppUser target = findUserForUpdateOrThrow(targetUserId);
    versionPreconditionService.requireCurrent(expectedVersion, target.getVersion(), "user");
    target.setStatus(ACTIVE);
    // FR-AUT-010: re-enabling a previously locked-out/disabled user must not carry over a stale
    // lockout from before they were disabled.
    target.setFailedLoginCount(0);
    target.setLockedUntil(null);
    target = appUserRepository.save(target);

    // Without this, a client that still holds its pre-disable refresh token and presents it after
    // reactivation would fall into TokenRotationService's reuse/"theft" branch - the token really
    // is already revoked (disable's own revokeAllActiveTokensForUser), but that's an expected,
    // benign state here, not an attack; a spurious theft_suspected flag would only pollute any
    // future monitoring built on that column. Sessions must be detached from those tokens first -
    // user_session.refresh_token_id has its own FK, and a still-referenced row can't be deleted.
    userSessionRepository.detachRevokedRefreshTokensForUser(targetUserId);
    refreshTokenRepository.deleteRevokedTokensForUser(targetUserId);

    writeAuditLog(actorUserId, "USER_REACTIVATED", targetUserId, null);
    return toSummary(target);
  }

  private void assertEmailAvailable(String email) {
    if (appUserRepository.existsByEmail(email)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already in use.");
    }
  }

  // The existsByEmail() pre-check above closes the common case with a clean error before any
  // write is attempted, but leaves a race window between two concurrent requests for the same
  // email - this is the backstop, translating the citext UNIQUE constraint's violation into the
  // same 409 rather than letting it surface as an unhandled 500.
  private AppUser saveOrRejectDuplicateEmail(AppUser user) {
    try {
      return appUserRepository.save(user);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already in use.", e);
    }
  }

  private AppUser findUserOrThrow(UUID userId) {
    return appUserRepository
        .findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  // #62: reactivateUser uses this instead of findUserOrThrow - see
  // AppUserRepository.findByIdForUpdate for why locking the target row first, before that method
  // does anything else, is what actually closes the deadlock it can otherwise hit racing
  // disableUser() on the same target. disableUser() itself does NOT use this - see
  // lockTargetAndActiveAdministrators and disableUser()'s own comment for why it needs a
  // different, unified lock instead.
  private AppUser findUserForUpdateOrThrow(UUID userId) {
    return appUserRepository
        .findByIdForUpdate(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private AppUser extractTargetOrThrow(List<AppUser> lockedRows, UUID targetUserId) {
    return lockedRows.stream()
        .filter(user -> user.getId().equals(targetUserId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private long countActiveAdministrators(List<AppUser> lockedRows) {
    return lockedRows.stream().filter(this::isCurrentlyActiveAdministrator).count();
  }

  private boolean isCurrentlyActiveAdministrator(AppUser user) {
    return SYSTEM_ADMINISTRATOR.equals(user.getRole()) && ACTIVE.equals(user.getStatus());
  }

  // editUser's demote path: no pre-locked active-administrator count is available here (editUser
  // only locks the target itself, via a plain, non-locking findUserOrThrow, and only reaches for
  // the broader lock at all when actually demoting an active administrator) - unlike
  // disableUser(), that single-query-per-call shape has no two-step lock-order hazard, so it's
  // safe for this overload to take its own lock here via countActiveAdministratorsForUpdate,
  // which uses the same id-ordered acquisition as lockTargetAndActiveAdministrators below.
  private void assertNotLastActiveAdministrator(AppUser target, String actionVerb) {
    if (!isCurrentlyActiveAdministrator(target)) {
      return;
    }
    // FR-USR-005: enforced here via a row-level lock over every active administrator, not a DB
    // constraint - a natural-key uniqueness/count constraint can't express "at least one," and
    // this must hold under concurrent requests, hence FOR UPDATE rather than a plain count().
    assertAtLeastOneOtherActiveAdministrator(
        appUserRepository.countActiveAdministratorsForUpdate(), actionVerb);
  }

  // disableUser's path: the caller has already locked the target and every active administrator
  // in one statement (lockTargetAndActiveAdministrators) - this overload reuses that count
  // instead of taking a second, separate lock here, which is exactly the pattern #62's follow-up
  // deadlock came from.
  private void assertNotLastActiveAdministrator(
      AppUser target, long activeAdministratorCount, String actionVerb) {
    if (!isCurrentlyActiveAdministrator(target)) {
      return;
    }
    assertAtLeastOneOtherActiveAdministrator(activeAdministratorCount, actionVerb);
  }

  private void assertAtLeastOneOtherActiveAdministrator(
      long activeAdministratorCount, String actionVerb) {
    if (activeAdministratorCount <= MINIMUM_ACTIVE_ADMINISTRATORS) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cannot " + actionVerb + " the last active administrator.");
    }
  }

  private void writeAuditLog(
      UUID actorUserId, String action, UUID targetUserId, Map<String, Object> details) {
    AdminAuditLog entry = new AdminAuditLog();
    entry.setActorUserId(actorUserId);
    entry.setAction(action);
    entry.setTargetUserId(targetUserId);
    if (details != null && !details.isEmpty()) {
      // Jackson 3's writeValueAsString throws the unchecked JacksonException, not a checked one -
      // nothing to catch here; a serialization failure on a simple Map<String, Object> of
      // strings would indicate a real bug, not a recoverable condition.
      entry.setDetails(objectMapper.writeValueAsString(details));
    }
    adminAuditLogRepository.save(entry);
  }

  private UserSummaryResponse toSummary(AppUser user) {
    return new UserSummaryResponse(
        user.getId(),
        user.getEmail(),
        user.getRole(),
        user.getStatus(),
        user.getLanguage(),
        VersionPreconditionService.persistedVersion(user.getVersion(), "user"));
  }
}
