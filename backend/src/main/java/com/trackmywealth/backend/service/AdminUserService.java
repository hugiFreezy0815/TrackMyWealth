package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.EditUserRequest;
import com.trackmywealth.backend.dto.UserRoleValues;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.entity.AdminAuditLog;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AdminAuditLogRepository;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.RefreshTokenRepository;
import com.trackmywealth.backend.repository.UserSessionRepository;
import com.trackmywealth.backend.validation.WorkspaceNames;
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

/** Administrator-driven user lifecycle management and workspace provisioning. */
@Service
public class AdminUserService {

  // Names the resource in a 412 VERSION_CONFLICT detail (VersionPreconditionService).
  private static final String VERSIONED_RESOURCE = "user";

  private static final String ACTIVE = "ACTIVE";

  // FR-USR-005: a workspace/deployment must always retain at least this many active
  // administrators - disabling/demoting one is only rejected when it would drop below this.
  private static final long MINIMUM_ACTIVE_ADMINISTRATORS = 1;

  private final AppUserRepository appUserRepository;
  private final WorkspaceProvisioningService workspaceProvisioningService;
  private final RefreshTokenRepository refreshTokenRepository;
  private final UserSessionRepository userSessionRepository;
  private final AdminAuditLogRepository adminAuditLogRepository;
  private final PasswordEncoder passwordEncoder;
  private final ObjectMapper objectMapper;
  private final VersionPreconditionService versionPreconditionService;

  public AdminUserService(
      AppUserRepository appUserRepository,
      WorkspaceProvisioningService workspaceProvisioningService,
      RefreshTokenRepository refreshTokenRepository,
      UserSessionRepository userSessionRepository,
      AdminAuditLogRepository adminAuditLogRepository,
      PasswordEncoder passwordEncoder,
      ObjectMapper objectMapper,
      VersionPreconditionService versionPreconditionService) {
    this.appUserRepository = appUserRepository;
    this.workspaceProvisioningService = workspaceProvisioningService;
    this.refreshTokenRepository = refreshTokenRepository;
    this.userSessionRepository = userSessionRepository;
    this.adminAuditLogRepository = adminAuditLogRepository;
    this.passwordEncoder = passwordEncoder;
    this.objectMapper = objectMapper;
    this.versionPreconditionService = versionPreconditionService;
  }

  @Transactional
  public UserSummaryResponse createUser(CreateUserRequest request, UUID actorUserId) {
    assertEmailAvailable(request.email());
    boolean standardUser = UserRoleValues.STANDARD_USER.equals(request.role());
    // A field combination the request alone gets wrong, so 400 rather than a state conflict. A
    // blank name means "no name" (WorkspaceNames), so it is no workspace setting.
    if (!standardUser
        && (WorkspaceNames.normalize(request.workspaceName()) != null
            || request.currencyCode() != null)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Workspace settings apply only to Standard Users.");
    }

    AppUser user = new AppUser();
    user.setEmail(request.email());
    user.setPasswordHash(passwordEncoder.encode(request.password()));
    user.setRole(request.role());
    user.setLanguage(request.language());
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("role", request.role());
    if (standardUser) {
      String currency =
          request.currencyCode() == null ? user.getReportingCurrency() : request.currencyCode();
      user.setReportingCurrency(currency);
      var member =
          workspaceProvisioningService.create(request.email(), request.workspaceName(), currency);
      user.setWorkspaceMember(member);
      audit.put("workspaceId", member.getWorkspace().getId());
      audit.put("workspaceMemberId", member.getId());
    }
    user = saveOrRejectDuplicateEmail(user);

    writeAuditLog(actorUserId, "USER_CREATED", user.getId(), audit);
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
    versionPreconditionService.requireCurrent(
        expectedVersion, target.getVersion(), VERSIONED_RESOURCE);
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
    // FR-USR-013: a role is fixed at creation. Rejected as bad input (400), not as a conflict a
    // refetch could resolve.
    if (request.role() != null && !request.role().equals(target.getRole())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A user's role cannot be changed.");
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
    versionPreconditionService.requireCurrent(
        expectedVersion, target.getVersion(), VERSIONED_RESOURCE);
    long activeAdministratorCount = countActiveAdministrators(lockedTargetAndAdministrators);
    assertNotLastActiveAdministrator(target, activeAdministratorCount, "disable");

    target.setStatus("DISABLED");
    target.incrementTokenVersion();
    target = appUserRepository.saveAndFlush(target);
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
    versionPreconditionService.requireCurrent(
        expectedVersion, target.getVersion(), VERSIONED_RESOURCE);
    target.setStatus(ACTIVE);
    // FR-AUT-010: re-enabling a previously locked-out/disabled user must not carry over a stale
    // lockout from before they were disabled.
    target.setFailedLoginCount(0);
    target.setLockedUntil(null);
    // Flushed so the returned ETag is the version the trigger stores, not the pre-update one.
    target = appUserRepository.saveAndFlush(target);

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
      return appUserRepository.saveAndFlush(user);
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
    return UserRoleValues.SYSTEM_ADMINISTRATOR.equals(user.getRole())
        && ACTIVE.equals(user.getStatus());
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
        VersionPreconditionService.persistedVersion(user.getVersion(), VERSIONED_RESOURCE));
  }
}
