package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.MfaEnrollmentResponse;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrDataFactory;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.OptionalLong;
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
 * <p>Three properties every code check here shares:
 *
 * <ul>
 *   <li><b>Single use.</b> A code is accepted only if its 30-second time step is strictly newer
 *       than the last accepted one ({@link AppUserRepository#markMfaStepUsed}, RFC 6238 section
 *       5.2) - an observed code cannot be replayed within its ~90-second validity. The practical
 *       consequence: two codes cannot both be used within the same 30 seconds.
 *   <li><b>Bounded guessing.</b> Every check first {@link LoginAttemptService#reserveAttempt
 *       reserves} an attempt from the per-account failure budget atomically, so parallel guesses
 *       cannot exceed it.
 *   <li><b>Targeted writes.</b> Only the MFA columns are ever written, via the repository's
 *       dedicated UPDATEs - never a {@code save()} of the whole user from a stale snapshot.
 * </ul>
 *
 * <p>Clock-skew tolerance is the RFC 6238 default (the current step and one either side). Known,
 * documented gap (per the story's own "Error/edge cases"): no lost-device recovery path (e.g.
 * backup codes) exists yet, and there is no administrator reset either - a user who loses their
 * authenticator can only be unlocked directly in the database (see the README).
 */
@Service
public class MfaService {

  private static final String ISSUER = "TrackMyWealth";
  private static final int DIGITS = 6;
  private static final int PERIOD_SECONDS = 30;
  private static final int ALLOWED_DRIFT_STEPS = 1;

  private final AppUserRepository appUserRepository;
  private final PasswordEncoder passwordEncoder;
  private final MfaEncryptionService mfaEncryptionService;
  private final LoginAttemptService loginAttemptService;
  private final Clock clock;
  private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
  private final QrDataFactory qrDataFactory =
      new QrDataFactory(HashingAlgorithm.SHA1, DIGITS, PERIOD_SECONDS);
  private final CodeGenerator codeGenerator =
      new DefaultCodeGenerator(HashingAlgorithm.SHA1, DIGITS);

  public MfaService(
      AppUserRepository appUserRepository,
      PasswordEncoder passwordEncoder,
      MfaEncryptionService mfaEncryptionService,
      LoginAttemptService loginAttemptService,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.passwordEncoder = passwordEncoder;
    this.mfaEncryptionService = mfaEncryptionService;
    this.loginAttemptService = loginAttemptService;
    this.clock = clock;
  }

  /**
   * Starts enrollment: generates a fresh secret, stores it encrypted, and returns the setup data -
   * without enabling MFA yet. Calling this again before confirming simply replaces the pending
   * secret. Once MFA is enabled it is refused (409) rather than replacing the live secret: doing so
   * would silently swap the secret login verifies against for one the user has not yet proven they
   * can generate codes from, locking them out. To change authenticator, disable first.
   */
  // noRollbackFor (here and on the other writers below): a wrong guess's reserved attempt must
  // persist even though the method throws to report it (see LoginAttemptService).
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public MfaEnrollmentResponse enroll(UUID userId, String password) {
    AppUser user = currentUser(userId);
    if (user.isMfaEnabled()) {
      throw alreadyEnabled();
    }
    loginAttemptService.reserveAttempt(userId);
    requireCurrentPassword(user, password);

    String secret = secretGenerator.generate();
    // The statement itself is conditional on MFA not being enabled, so this also holds if a
    // confirm raced this call between the check above and here.
    if (appUserRepository.startMfaEnrollment(userId, mfaEncryptionService.encrypt(secret)) == 0) {
      throw alreadyEnabled();
    }
    loginAttemptService.clearFailedAttempts(userId);

    QrData qrData =
        qrDataFactory.newBuilder().label(user.getEmail()).secret(secret).issuer(ISSUER).build();
    return new MfaEnrollmentResponse(secret, qrData.getUri());
  }

  /** Confirms enrollment: only after this does {@code mfa_enabled} become true. */
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public void confirm(UUID userId, String code) {
    AppUser user = currentUser(userId);
    if (user.isMfaEnabled()) {
      throw alreadyEnabled();
    }
    if (user.getMfaTotpSecret() == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "No MFA enrollment in progress.");
    }
    loginAttemptService.reserveAttempt(userId);
    // The confirming code is consumed too: it must not be replayable as the first login code.
    if (!consumeValidCode(user, code)) {
      throw invalidCode();
    }
    if (appUserRepository.confirmMfaEnrollment(userId) == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "No MFA enrollment in progress.");
    }
    loginAttemptService.clearFailedAttempts(userId);
  }

  /**
   * Turns MFA off. Requires the password, and - while MFA is enabled - a valid current code as
   * well: a stolen session plus a reused password must not be enough to permanently remove the
   * second factor. With only a pending (unconfirmed) enrollment there is no working authenticator
   * to ask a code of, so the password alone cancels it.
   */
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public void disable(UUID userId, String password, String code) {
    AppUser user = currentUser(userId);
    if (user.isMfaEnabled() && code == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "A current authenticator code is required to disable MFA.");
    }
    loginAttemptService.reserveAttempt(userId);
    requireCurrentPassword(user, password);
    if (user.isMfaEnabled() && !consumeValidCode(user, code)) {
      throw invalidCode();
    }
    appUserRepository.clearMfa(userId);
    loginAttemptService.clearFailedAttempts(userId);
  }

  /**
   * Step 2 of login for an MFA-enabled user - called with the user resolved from a challenge token
   * already validated by {@link MfaChallengeTokenService}, and after the caller has reserved an
   * attempt. Consumes the code on success.
   */
  public boolean verifyLoginCode(AppUser user, String code) {
    return user.isMfaEnabled() && consumeValidCode(user, code);
  }

  // True only if `code` is valid for one of the accepted time steps AND that step is newer than
  // any previously accepted one (the conditional UPDATE is what makes it single-use).
  private boolean consumeValidCode(AppUser user, String code) {
    if (user.getMfaTotpSecret() == null) {
      return false;
    }
    OptionalLong step = matchingStep(mfaEncryptionService.decrypt(user.getMfaTotpSecret()), code);
    return step.isPresent()
        && appUserRepository.markMfaStepUsed(user.getId(), step.getAsLong()) == 1;
  }

  // The newest accepted time step whose code equals `code`, if any. Always computes and compares
  // every accepted step (no early return) and compares in constant time, so neither the response
  // time nor an early exit says which step - or how much of the code - matched.
  private OptionalLong matchingStep(String secret, String code) {
    long currentStep = Math.floorDiv(clock.instant().getEpochSecond(), (long) PERIOD_SECONDS);
    byte[] presented = code.getBytes(StandardCharsets.UTF_8);
    OptionalLong matched = OptionalLong.empty();
    for (int drift = -ALLOWED_DRIFT_STEPS; drift <= ALLOWED_DRIFT_STEPS; drift++) {
      long step = currentStep + drift;
      byte[] expected = generateCode(secret, step).getBytes(StandardCharsets.UTF_8);
      if (MessageDigest.isEqual(expected, presented)) {
        matched = OptionalLong.of(step);
      }
    }
    return matched;
  }

  private String generateCode(String secret, long step) {
    try {
      return codeGenerator.generate(secret, step);
    } catch (CodeGenerationException e) {
      throw new IllegalStateException("Failed to generate TOTP code", e);
    }
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

  // The story's "recent re-authentication" (FR-AUT-012) precondition. The caller has already
  // reserved an attempt from the shared per-account budget: without that, someone holding only a
  // stolen access token could guess the password without limit through these endpoints - exactly
  // the attacker re-authentication exists to stop.
  private void requireCurrentPassword(AppUser user, String password) {
    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect password.");
    }
  }

  private ResponseStatusException invalidCode() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired code.");
  }

  private ResponseStatusException alreadyEnabled() {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "MFA is already enabled. Disable it first to change authenticator.");
  }
}
