package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.MfaVerifyRequest;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-02-02: email+password login, issuing tokens via {@link TokenIssuanceService} on success. For
 * an MFA-enabled user (US-02-04), password success alone does not issue tokens - it issues an
 * {@link MfaChallengeTokenService} challenge instead; {@code POST /api/v1/auth/mfa/verify} (backed
 * by {@link MfaService#verifyLoginCode}) completes the login with a valid TOTP code.
 */
@Service
public class LoginService {

  private static final String ACTIVE = "ACTIVE";

  private final AppUserRepository appUserRepository;
  private final PasswordEncoder passwordEncoder;
  private final TokenIssuanceService tokenIssuanceService;
  private final MfaChallengeTokenService mfaChallengeTokenService;
  private final MfaService mfaService;
  private final LoginAttemptService loginAttemptService;

  // A password nobody can ever have chosen, hashed once at startup so a login attempt against a
  // nonexistent email still pays the same Argon2 cost as one that finds a real account - otherwise
  // the response-time difference between "no such user" and "wrong password" would itself leak
  // whether an email is registered.
  private final String dummyPasswordHash;

  public LoginService(
      AppUserRepository appUserRepository,
      PasswordEncoder passwordEncoder,
      TokenIssuanceService tokenIssuanceService,
      MfaChallengeTokenService mfaChallengeTokenService,
      MfaService mfaService,
      LoginAttemptService loginAttemptService) {
    this.appUserRepository = appUserRepository;
    this.passwordEncoder = passwordEncoder;
    this.tokenIssuanceService = tokenIssuanceService;
    this.mfaChallengeTokenService = mfaChallengeTokenService;
    this.mfaService = mfaService;
    this.loginAttemptService = loginAttemptService;
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

    loginAttemptService.requireNotLocked(user);

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      loginAttemptService.registerFailedAttempt(user.getId());
      throw invalidCredentials();
    }

    if (user.isMfaEnabled()) {
      // Deliberately does NOT reset the failure counter yet (US-02-04): a correct password alone
      // isn't a completed login, and resetting here would let an attacker who has the password
      // re-run this step between batches of wrong TOTP guesses to keep their budget topped up.
      // verifyMfaChallenge resets it only once the second factor is proven too.
      return new LoginResponse(true, mfaChallengeTokenService.issue(user.getId()), null);
    }
    loginAttemptService.registerSuccessfulLogin(user.getId());
    AuthTokensResponse tokens = tokenIssuanceService.issueTokens(user, deviceLabel, rawIpAddress);
    return new LoginResponse(false, null, tokens);
  }

  /**
   * Step 2 of login for an MFA-enabled user (US-02-04) - completes what {@link #login} started,
   * exchanging a still-valid challenge token plus a correct TOTP code for real tokens. A user whose
   * status or MFA state changed since step 1 (disabled, MFA turned off) is rejected here too, not
   * just re-checked at the next ordinary login - the challenge token alone must never be
   * sufficient.
   */
  // noRollbackFor: same reason as login() - a wrong code's failed-attempt increment must persist
  // even though the method throws to report it.
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public AuthTokensResponse verifyMfaChallenge(
      MfaVerifyRequest request, String deviceLabel, String rawIpAddress) {
    UUID userId =
        mfaChallengeTokenService
            .parse(request.challengeToken())
            .orElseThrow(this::invalidChallenge);
    AppUser user = appUserRepository.findById(userId).orElseThrow(this::invalidChallenge);

    if (!ACTIVE.equals(user.getStatus())) {
      throw invalidChallenge();
    }
    loginAttemptService.requireNotLocked(user);
    if (!mfaService.verifyLoginCode(user, request.code())) {
      loginAttemptService.registerFailedAttempt(user.getId());
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired code.");
    }

    loginAttemptService.registerSuccessfulLogin(user.getId());
    return tokenIssuanceService.issueTokens(user, deviceLabel, rawIpAddress);
  }

  private ResponseStatusException invalidCredentials() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
  }

  private ResponseStatusException invalidChallenge() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired challenge.");
  }
}
