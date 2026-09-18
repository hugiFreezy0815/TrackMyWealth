package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.MfaEnrollmentResponse;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrDataFactory;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-02-04: TOTP (RFC 6238) enrollment, confirmation, disablement, and login-time code
 * verification. {@link #ISSUER} is what an authenticator app displays as the account's issuing
 * service; {@code app_user.email} is the per-account label.
 *
 * <p>Clock-skew tolerance and step size are the library's own RFC 6238 defaults (30s step, ±1 step
 * accepted either side) - not reconfigured, since nothing in the story calls for a different value.
 * Known, documented gap (per the story's own "Error/edge cases"): no lost-device recovery path
 * (e.g. backup codes) exists yet, and there is no administrator reset either - a user who loses
 * their authenticator can only be unlocked directly in the database (see the README).
 */
@Service
public class MfaService {

  private static final String ISSUER = "TrackMyWealth";
  private static final int DIGITS = 6;
  private static final int PERIOD_SECONDS = 30;

  private final AppUserRepository appUserRepository;
  private final PasswordEncoder passwordEncoder;
  private final MfaEncryptionService mfaEncryptionService;
  private final LoginAttemptService loginAttemptService;
  private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
  private final QrDataFactory qrDataFactory =
      new QrDataFactory(HashingAlgorithm.SHA1, DIGITS, PERIOD_SECONDS);
  private final CodeVerifier codeVerifier =
      new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());

  public MfaService(
      AppUserRepository appUserRepository,
      PasswordEncoder passwordEncoder,
      MfaEncryptionService mfaEncryptionService,
      LoginAttemptService loginAttemptService) {
    this.appUserRepository = appUserRepository;
    this.passwordEncoder = passwordEncoder;
    this.mfaEncryptionService = mfaEncryptionService;
    this.loginAttemptService = loginAttemptService;
  }

  /**
   * Starts enrollment: generates a fresh secret, stores it encrypted, and returns the setup data -
   * without enabling MFA yet. Calling this again before confirming simply replaces the pending
   * secret. Once MFA is enabled it is refused (409) rather than replacing the live secret: doing so
   * would silently swap the secret login verifies against for one the user has not yet proven they
   * can generate codes from, locking them out. To change authenticator, disable first.
   */
  // noRollbackFor: a wrong password's failed-attempt increment must persist even though the
  // method throws to report it (see LoginAttemptService).
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public MfaEnrollmentResponse enroll(UUID userId, String password) {
    AppUser user = currentUser(userId);
    requireCurrentPassword(user, password);
    if (user.isMfaEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "MFA is already enabled. Disable it before enrolling again.");
    }

    String secret = secretGenerator.generate();
    user.startMfaEnrollment(mfaEncryptionService.encrypt(secret));
    appUserRepository.save(user);

    QrData qrData =
        qrDataFactory.newBuilder().label(user.getEmail()).secret(secret).issuer(ISSUER).build();
    return new MfaEnrollmentResponse(secret, qrData.getUri());
  }

  /** Confirms enrollment: only after this does {@code mfa_enabled} become true. */
  @Transactional
  public void confirm(UUID userId, String code) {
    AppUser user = currentUser(userId);
    if (user.getMfaTotpSecret() == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "No MFA enrollment in progress.");
    }
    if (!verifyCode(user, code)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired code.");
    }
    user.confirmMfaEnrollment();
    appUserRepository.save(user);
  }

  @Transactional(noRollbackFor = ResponseStatusException.class)
  public void disable(UUID userId, String password) {
    AppUser user = currentUser(userId);
    requireCurrentPassword(user, password);
    appUserRepository.clearMfa(userId);
  }

  /**
   * Step 2 of login for an MFA-enabled user - called with the user resolved from a challenge token
   * already validated by {@link MfaChallengeTokenService}.
   */
  public boolean verifyLoginCode(AppUser user, String code) {
    return user.isMfaEnabled() && verifyCode(user, code);
  }

  // JwtAuthenticationFilter already confirmed this user exists and is ACTIVE before the request
  // reached a controller - findById() failing would mean the row vanished since, not a normal
  // outcome.
  private AppUser currentUser(UUID userId) {
    return appUserRepository
        .findById(userId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists."));
  }

  private boolean verifyCode(AppUser user, String code) {
    String secret = mfaEncryptionService.decrypt(user.getMfaTotpSecret());
    return codeVerifier.isValidCode(secret, code);
  }

  // The story's "recent re-authentication" (FR-AUT-012) precondition. Draws from the same
  // per-account failure budget as login: without it, someone holding only a stolen access token
  // could guess the password without limit through these endpoints - exactly the attacker
  // re-authentication exists to stop.
  private void requireCurrentPassword(AppUser user, String password) {
    loginAttemptService.requireNotLocked(user);
    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
      loginAttemptService.registerFailedAttempt(user.getId());
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect password.");
    }
  }
}
