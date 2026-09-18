package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-AUT-010's per-account lockout, shared by every flow that checks a credential a guesser could
 * brute-force: the password at login ({@link LoginService}), the TOTP code at login's second step
 * (US-02-04), and the password re-entered to enroll in or disable MFA ({@link MfaService}). One
 * implementation means a guess against any of them draws from the same failure budget - an attacker
 * cannot get fresh attempts by switching to a different endpoint.
 *
 * <p>Callers that report a failure by throwing must run in a transaction that does not roll back on
 * {@link ResponseStatusException} ({@code noRollbackFor}), or the increment written here is
 * discarded together with the failed request.
 */
@Service
public class LoginAttemptService {

  // Architect decision (US-02-02): a fixed lockout window, not a rolling one, since app_user has
  // no "attempts in the last N minutes" column, only a cumulative failed_login_count plus
  // locked_until. This only ever limits per account - complemented by RateLimitFilter's
  // per-source (IP) limiting (#48), which catches an attacker spraying different accounts from one
  // IP without ever tripping any single account's lockout.
  private static final int MAX_FAILED_ATTEMPTS_BEFORE_LOCKOUT = 5;
  private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

  private final AppUserRepository appUserRepository;

  public LoginAttemptService(AppUserRepository appUserRepository) {
    this.appUserRepository = appUserRepository;
  }

  public void requireNotLocked(AppUser user) {
    if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now())) {
      throw new ResponseStatusException(
          HttpStatus.LOCKED, "Account is temporarily locked. Try again later.");
    }
  }

  // Atomic increment (AppUserRepository.registerFailedLoginAttempt), not a save() of a mutated
  // entity - AppUser carries a real @Version column, and two concurrent wrong guesses loading the
  // same row would otherwise have one lose to an uncaught ObjectOptimisticLockingFailureException
  // (a 500) instead of both correctly counting toward the lockout.
  public void registerFailedAttempt(UUID userId) {
    appUserRepository.registerFailedLoginAttempt(
        userId, MAX_FAILED_ATTEMPTS_BEFORE_LOCKOUT, now().plus(LOCKOUT_DURATION));
  }

  // Same reasoning as registerFailedAttempt above - an atomic reset rather than a save() that a
  // concurrent login (e.g. two devices signing in at once) could lose to a version conflict.
  public void registerSuccessfulLogin(UUID userId) {
    appUserRepository.registerSuccessfulLogin(userId, now());
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
