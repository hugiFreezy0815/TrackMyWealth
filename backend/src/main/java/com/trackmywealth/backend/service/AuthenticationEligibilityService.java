package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AppUserRepository;
import org.springframework.stereotype.Service;

/**
 * Whether a user may be issued tokens at all (US-02-06): an active account and, if linked, an
 * active workspace member - the rule {@code JwtAuthenticationFilter} applies to every access token.
 * Login, MFA verification and refresh check it too, so none of them issues a token the filter would
 * reject on first use: a client could then never tell "renew the session" from "go back to
 * sign-in". No membership is fine: a SYSTEM_ADMINISTRATOR without one is a normal state.
 */
@Service
public class AuthenticationEligibilityService {

  private static final String ACTIVE = "ACTIVE";

  private final AppUserRepository appUserRepository;

  public AuthenticationEligibilityService(AppUserRepository appUserRepository) {
    this.appUserRepository = appUserRepository;
  }

  public boolean mayAuthenticate(AppUser user) {
    return ACTIVE.equals(user.getStatus())
        && appUserRepository
            .findWorkspaceMemberStatus(user.getId())
            .map(ACTIVE::equals)
            .orElse(true);
  }
}
