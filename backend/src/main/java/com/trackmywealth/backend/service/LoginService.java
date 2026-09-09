package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-02-02: email+password login, issuing tokens via {@link TokenIssuanceService} on success.
 *
 * <p>Architect decision for this story: MFA verification (US-02-04) is deliberately not implemented
 * here - only the branch point for it exists, gated on {@code mfa_enabled}, which every user
 * created so far always has {@code false}, so the branch never actually triggers yet.
 */
@Service
public class LoginService {

  // FR-AUT-010: architect decision for this story - a fixed lockout window, not a rolling one,
  // since app_user has no "attempts in the last N minutes" column, only a cumulative
  // failed_login_count plus locked_until. This only ever limits per account - complemented by
  // RateLimitFilter's per-source (IP) limiting (#48), added later, which catches an attacker
  // spraying different accounts from one IP without ever tripping any single account's lockout.
  private static final int MAX_FAILED_ATTEMPTS_BEFORE_LOCKOUT = 5;
  private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
  private static final String ACTIVE = "ACTIVE";

  private final AppUserRepository appUserRepository;
  private final PasswordEncoder passwordEncoder;
  private final TokenIssuanceService tokenIssuanceService;

  // A password nobody can ever have chosen, hashed once at startup so a login attempt against a
  // nonexistent email still pays the same Argon2 cost as one that finds a real account - otherwise
  // the response-time difference between "no such user" and "wrong password" would itself leak
  // whether an email is registered.
  private final String dummyPasswordHash;

  public LoginService(
      AppUserRepository appUserRepository,
      PasswordEncoder passwordEncoder,
      TokenIssuanceService tokenIssuanceService) {
    this.appUserRepository = appUserRepository;
    this.passwordEncoder = passwordEncoder;
    this.tokenIssuanceService = tokenIssuanceService;
    this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  /**
   * @param rawIpAddress see {@link TokenIssuanceService#issueTokens}; passed through unchanged.
   */
  // noRollbackFor: a failed attempt's whole point is to persist the incremented
  // failed_login_count/locked_until even though the method also throws to report the failure to
  // the caller - Spring's default rollback-on-any-unchecked-exception would otherwise silently
  // discard that write, since ResponseStatusException is unchecked.
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public LoginResponse login(LoginRequest request, String deviceLabel, String rawIpAddress) {
    AppUser user = appUserRepository.findByEmail(request.email()).orElse(null);
    if (user == null) {
      passwordEncoder.matches(request.password(), dummyPasswordHash);
      throw invalidCredentials();
    }

    // Checked - and rejected generically, without ever comparing the real password hash - before
    // the lockout/password checks below: a disabled account must never become a password-validity
    // oracle. Reaching a status-specific error only after the real hash comparison would let a
    // caller who already has (or is brute-forcing) the password confirm it was correct purely
    // from the response differing for a disabled account.
    if (!ACTIVE.equals(user.getStatus())) {
      passwordEncoder.matches(request.password(), dummyPasswordHash);
      throw invalidCredentials();
    }

    if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now())) {
      throw new ResponseStatusException(
          HttpStatus.LOCKED, "Account is temporarily locked. Try again later.");
    }

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      registerFailedAttempt(user);
      throw invalidCredentials();
    }

    registerSuccessfulLogin(user);

    if (user.isMfaEnabled()) {
      return new LoginResponse(true, null);
    }
    AuthTokensResponse tokens = tokenIssuanceService.issueTokens(user, deviceLabel, rawIpAddress);
    return new LoginResponse(false, tokens);
  }

  // Atomic increment (AppUserRepository.registerFailedLoginAttempt), not a save() of a mutated
  // entity - AppUser carries a real @Version column, and two concurrent wrong-password attempts
  // loading the same row would otherwise have one lose to an uncaught
  // ObjectOptimisticLockingFailureException (a 500) instead of both correctly counting toward the
  // lockout.
  private void registerFailedAttempt(AppUser user) {
    appUserRepository.registerFailedLoginAttempt(
        user.getId(), MAX_FAILED_ATTEMPTS_BEFORE_LOCKOUT, now().plus(LOCKOUT_DURATION));
  }

  // Same reasoning as registerFailedAttempt above - an atomic reset rather than a save() that a
  // concurrent login (e.g. two devices signing in at once) could lose to a version conflict.
  private void registerSuccessfulLogin(AppUser user) {
    appUserRepository.registerSuccessfulLogin(user.getId(), now());
  }

  private ResponseStatusException invalidCredentials() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
