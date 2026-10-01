package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.config.JwtProperties;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.RefreshToken;
import com.trackmywealth.backend.entity.UserSession;
import com.trackmywealth.backend.repository.RefreshTokenRepository;
import com.trackmywealth.backend.repository.UserSessionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestration of FR-AUT-004's theft response in each branch of {@link
 * TokenRotationService#rotate}. The SQL itself (and the real concurrency behaviour) is covered
 * against PostgreSQL by {@code AuthControllerTest}.
 */
class TokenRotationServiceTest {

  private static final UUID REFRESH_TOKEN_FAMILY_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String PLAINTEXT_TOKEN = "presented-refresh-token";
  private static final String TOKEN_HASH = "hash";

  private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
  private final UserSessionRepository userSessionRepository = mock(UserSessionRepository.class);
  private final JwtService jwtService = mock(JwtService.class);
  private final TokenHashingService tokenHashingService = mock(TokenHashingService.class);
  private final JwtProperties jwtProperties =
      new JwtProperties("trackmywealth", 15, 30, "test-secret-that-is-at-least-32-bytes-long");

  private final TokenRotationService service =
      new TokenRotationService(
          refreshTokenRepository,
          userSessionRepository,
          jwtService,
          jwtProperties,
          tokenHashingService);

  @Test
  void reuseOfRevokedRefreshTokenRevokesTokenFamilyAndAccessSessionBeforeRejectingRequest() {
    RefreshToken reusedToken = presentedToken();
    reusedToken.markRotatedOutBy(
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

    assertRejectedAsAlreadyUsed();

    verifyFamilyThenSessionRevoked();
    verify(refreshTokenRepository, never()).save(any());
    verify(jwtService, never()).issueAccessToken(any(), anyInt(), any());
  }

  @Test
  void losingAConcurrentRotationDiscardsTheNewTokenAndRevokesTokenFamilyAndAccessSession() {
    presentedToken();
    when(refreshTokenRepository.save(any(RefreshToken.class))).then(returnsFirstArg());
    when(refreshTokenRepository.markRotatedOutByIfStillActive(any(), any(), any())).thenReturn(0);

    assertRejectedAsAlreadyUsed();

    verify(refreshTokenRepository).delete(any(RefreshToken.class));
    verifyFamilyThenSessionRevoked();
    verify(userSessionRepository, never()).repointRefreshToken(any(), any(), any());
    verify(jwtService, never()).issueAccessToken(any(), anyInt(), any());
  }

  @Test
  void rotatingIntoANoLongerActiveSessionRevokesTokenFamilyInsteadOfIssuingTokens() {
    presentedToken();
    when(refreshTokenRepository.save(any(RefreshToken.class))).then(returnsFirstArg());
    when(refreshTokenRepository.markRotatedOutByIfStillActive(any(), any(), any())).thenReturn(1);
    when(userSessionRepository.findByRefreshToken_Id(any()))
        .thenReturn(Optional.of(new UserSession()));
    when(userSessionRepository.repointRefreshToken(any(), any(), any())).thenReturn(0);

    assertRejectedAsAlreadyUsed();

    verifyFamilyThenSessionRevoked();
    verify(jwtService, never()).issueAccessToken(any(), anyInt(), any());
  }

  @Test
  void rotatingIntoAnActiveSessionIssuesTokensWithoutRevokingTheFamily() {
    presentedToken();
    when(refreshTokenRepository.save(any(RefreshToken.class))).then(returnsFirstArg());
    when(refreshTokenRepository.markRotatedOutByIfStillActive(any(), any(), any())).thenReturn(1);
    when(userSessionRepository.findByRefreshToken_Id(any()))
        .thenReturn(Optional.of(new UserSession()));
    when(userSessionRepository.repointRefreshToken(any(), any(), any())).thenReturn(1);
    when(tokenHashingService.generateOpaqueToken()).thenReturn("next-refresh-token");
    when(jwtService.issueAccessToken(any(), anyInt(), any())).thenReturn("access-token");

    assertThat(service.rotate(PLAINTEXT_TOKEN).refreshToken()).isEqualTo("next-refresh-token");

    verify(refreshTokenRepository, never()).markFamilyAsTheftSuspected(any(), any());
    verify(userSessionRepository, never()).revokeActiveSessionsByRefreshTokenFamilyId(any(), any());
  }

  private RefreshToken presentedToken() {
    AppUser activeUser = new AppUser();
    activeUser.setStatus("ACTIVE");

    RefreshToken token = new RefreshToken();
    token.setUser(activeUser);
    token.setFamilyId(REFRESH_TOKEN_FAMILY_ID);
    token.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

    when(tokenHashingService.sha256Hex(PLAINTEXT_TOKEN)).thenReturn(TOKEN_HASH);
    when(refreshTokenRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(token));
    return token;
  }

  private void assertRejectedAsAlreadyUsed() {
    assertThatThrownBy(() -> service.rotate(PLAINTEXT_TOKEN))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> {
              assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(exception.getReason()).contains("already been used");
            });
  }

  // Refresh-token rows before the session row - the lock order shared with SessionService.
  private void verifyFamilyThenSessionRevoked() {
    InOrder securityResponseOrder = inOrder(refreshTokenRepository, userSessionRepository);
    securityResponseOrder
        .verify(refreshTokenRepository)
        .markFamilyAsTheftSuspected(eq(REFRESH_TOKEN_FAMILY_ID), any(OffsetDateTime.class));
    securityResponseOrder
        .verify(userSessionRepository)
        .revokeActiveSessionsByRefreshTokenFamilyId(
            eq(REFRESH_TOKEN_FAMILY_ID), any(OffsetDateTime.class));
  }
}
