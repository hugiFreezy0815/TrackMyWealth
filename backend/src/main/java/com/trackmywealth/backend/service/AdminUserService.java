package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.EditUserRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.entity.AdminAuditLog;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AdminAuditLogRepository;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.RefreshTokenRepository;
import com.trackmywealth.backend.repository.UserSessionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
 * US-02-01: administrator-driven user lifecycle management. Deliberately touches only {@code
 * app_user}/{@code refresh_token}/{@code admin_audit_log} - never a workspace-scoped table, per
 * FR-TEN-007's separation between administration rights and financial-data access.
 */
@Service
public class AdminUserService {

  private static final String SYSTEM_ADMINISTRATOR = "SYSTEM_ADMINISTRATOR";
  private static final String ACTIVE = "ACTIVE";

  // FR-USR-005: a workspace/deployment must always retain at least this many active
  // administrators - disabling/demoting one is only rejected when it would drop below this.
  private static final long MINIMUM_ACTIVE_ADMINISTRATORS = 1;

  private final AppUserRepository appUserRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final UserSessionRepository userSessionRepository;
  private final AdminAuditLogRepository adminAuditLogRepository;
  private final PasswordEncoder passwordEncoder;
  private final ObjectMapper objectMapper;

  public AdminUserService(
      AppUserRepository appUserRepository,
      RefreshTokenRepository refreshTokenRepository,
      UserSessionRepository userSessionRepository,
      AdminAuditLogRepository adminAuditLogRepository,
      PasswordEncoder passwordEncoder,
      ObjectMapper objectMapper) {
    this.appUserRepository = appUserRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.userSessionRepository = userSessionRepository;
    this.adminAuditLogRepository = adminAuditLogRepository;
    this.passwordEncoder = passwordEncoder;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public UserSummaryResponse createUser(CreateUserRequest request, UUID actorUserId) {
    assertEmailAvailable(request.email());
    AppUser user = new AppUser();
    user.setEmail(request.email());
    user.setPasswordHash(passwordEncoder.encode(request.password()));
    user.setRole(request.role());
    user.setLanguage(request.language());
    user = saveOrRejectDuplicateEmail(user);

    writeAuditLog(actorUserId, "USER_CREATED", user.getId(), Map.of("role", request.role()));
    return toSummary(user);
  }

  @Transactional
  public UserSummaryResponse editUser(
      UUID targetUserId, EditUserRequest request, UUID actorUserId) {
    AppUser target = findUserOrThrow(targetUserId);
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
  public UserSummaryResponse disableUser(UUID targetUserId, UUID actorUserId) {
    AppUser target = findUserOrThrow(targetUserId);
    assertNotLastActiveAdministrator(target, "disable");

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
  public UserSummaryResponse reactivateUser(UUID targetUserId, UUID actorUserId) {
    AppUser target = findUserOrThrow(targetUserId);
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

  private void assertNotLastActiveAdministrator(AppUser target, String actionVerb) {
    boolean targetIsCurrentlyActiveAdministrator =
        SYSTEM_ADMINISTRATOR.equals(target.getRole()) && ACTIVE.equals(target.getStatus());
    if (!targetIsCurrentlyActiveAdministrator) {
      return;
    }
    // FR-USR-005: enforced here via a row-level lock over every active administrator, not a DB
    // constraint - a natural-key uniqueness/count constraint can't express "at least one," and
    // this must hold under concurrent requests, hence FOR UPDATE rather than a plain count().
    long activeAdministratorCount = appUserRepository.countActiveAdministratorsForUpdate();
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
        user.getId(), user.getEmail(), user.getRole(), user.getStatus(), user.getLanguage());
  }
}
