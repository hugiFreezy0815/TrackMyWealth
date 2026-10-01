package com.trackmywealth.backend.security;

import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the {@code Authorization: Bearer <token>} header on every request. Deliberately never
 * rejects a request itself - an invalid, missing, expired, or stale (see {@code tokenVersion}
 * below) token simply leaves {@link SecurityContextHolder} empty, so the request proceeds as
 * anonymous and {@code SecurityConfig}'s {@code authorizeHttpRequests} rules make the actual
 * allow/deny decision (a protected endpoint denies it; a {@code permitAll} one still works).
 *
 * <p>Only this - JWT signature/expiry validation, plus the {@code token_version} staleness check
 * that makes user-wide revocation real (FR-AUT-005), the per-session {@code user_session.status}
 * check that makes single-session revocation real (US-02-03), and (US-04-01) a linked {@code
 * workspace_member}'s own {@code status} - not login or refresh-token rotation, which is US-02-02's
 * job. Every downstream story that needs to know who is calling (US-02-01's admin endpoints today)
 * depends on this filter having already populated {@link AuthenticatedUserPrincipal}.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtService jwtService;
  private final AppUserRepository appUserRepository;

  public JwtAuthenticationFilter(JwtService jwtService, AppUserRepository appUserRepository) {
    this.jwtService = jwtService;
    this.appUserRepository = appUserRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    authenticate(request).ifPresent(SecurityContextHolder.getContext()::setAuthentication);
    filterChain.doFilter(request, response);
  }

  private Optional<UsernamePasswordAuthenticationToken> authenticate(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return Optional.empty();
    }
    String token = header.substring(BEARER_PREFIX.length());

    return jwtService
        .parseAccessToken(token)
        .flatMap(
            claims ->
                appUserRepository
                    .findAuthSnapshot(claims.userId(), claims.sessionId())
                    .flatMap(snapshot -> toToken(claims, snapshot)));
  }

  private Optional<UsernamePasswordAuthenticationToken> toToken(
      AccessTokenClaims claims, AppUserAuthSnapshot snapshot) {
    if (!"ACTIVE".equals(snapshot.status())
        || snapshot.tokenVersion() != claims.tokenVersion()
        || !"ACTIVE".equals(snapshot.sessionStatus())
        || (snapshot.workspaceMemberStatus() != null
            && !"ACTIVE".equals(snapshot.workspaceMemberStatus()))) {
      // Disabled since the token was issued, the token predates a revocation event (logout-all,
      // password change, role change, ...) that bumped token_version, this specific session
      // (US-02-03) was individually revoked, or (US-04-01) a linked workspace_member has itself
      // been deactivated - either way, this token no longer authenticates, regardless of its own
      // unexpired signature. A null workspaceMemberStatus (no membership at all) is deliberately
      // NOT rejected here - a SYSTEM_ADMINISTRATOR with no linked workspace_member is a normal
      // state (see AuthenticatedUserPrincipal's Javadoc) that must still authenticate for
      // admin-only endpoints; RLS then denies every workspace-scoped table by default for them.
      return Optional.empty();
    }
    AuthenticatedUserPrincipal principal =
        new AuthenticatedUserPrincipal(
            snapshot.userId(), snapshot.role(), snapshot.workspaceId(), claims.sessionId(), snapshot.language());
    List<SimpleGrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("ROLE_" + snapshot.role()));
    return Optional.of(new UsernamePasswordAuthenticationToken(principal, null, authorities));
  }
}
