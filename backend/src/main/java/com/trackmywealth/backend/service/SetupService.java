package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserRoleValues;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** One-time bootstrap of the workspace-free system administrator. */
@Service
public class SetupService {

  // An arbitrary, reserved PostgreSQL advisory lock key scoped to this one bootstrap flow.
  // pg_advisory_xact_lock is transaction-scoped and releases automatically at commit/rollback, so
  // no matching unlock call is needed. Two concurrent setup attempts serialize on this lock; the
  // second one only proceeds - and then correctly observes count() > 0 - after the first commits.
  private static final long SETUP_BOOTSTRAP_LOCK_KEY = 727100010103L;

  private final EntityManager entityManager;
  private final AppUserRepository appUserRepository;
  private final PasswordEncoder passwordEncoder;
  private final TokenIssuanceService tokenIssuanceService;

  public SetupService(
      EntityManager entityManager,
      AppUserRepository appUserRepository,
      PasswordEncoder passwordEncoder,
      TokenIssuanceService tokenIssuanceService) {
    this.entityManager = entityManager;
    this.appUserRepository = appUserRepository;
    this.passwordEncoder = passwordEncoder;
    this.tokenIssuanceService = tokenIssuanceService;
  }

  @Transactional
  public AuthTokensResponse bootstrapInitialAdministrator(
      SetupAdministratorRequest request, String deviceLabel, String rawIpAddress) {
    acquireSetupBootstrapLock();
    if (appUserRepository.count() > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Setup has already been completed for this deployment.");
    }

    AppUser administrator = new AppUser();
    administrator.setEmail(request.email());
    administrator.setPasswordHash(passwordEncoder.encode(request.password()));
    administrator.setRole(UserRoleValues.SYSTEM_ADMINISTRATOR);
    administrator.setReportingCurrency(request.currencyCode());
    administrator = appUserRepository.save(administrator);

    return tokenIssuanceService.issueTokens(administrator, deviceLabel, rawIpAddress);
  }

  private void acquireSetupBootstrapLock() {
    entityManager
        .createNativeQuery("SELECT pg_advisory_xact_lock(:lockKey)")
        .setParameter("lockKey", SETUP_BOOTSTRAP_LOCK_KEY)
        .getSingleResult();
  }
}
