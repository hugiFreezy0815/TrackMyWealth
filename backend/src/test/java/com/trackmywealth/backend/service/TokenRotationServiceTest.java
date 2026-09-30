package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.config.JwtProperties;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.RefreshToken;
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

class TokenRotationServiceTest {

  private static final UUID REFRESH_TOKEN_FAMILY_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private final RefreshTokenRepository refreshTokenRepository =
      org.mockito.Mockito.mock(RefreshTokenRepository.class);
  private final UserSessionRepository userSessionRepository =
      org.mockito.Mockito.mock(UserSessionRepository.class);
  private final JwtService jwtService = org.mockito.Mockito.mock(JwtService.class);
  private final TokenHashingService tokenHashingService =
      org.mockito.Mockito.mock(TokenHashingService.class);
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
    String plaintextToken = "already-used-refresh-token";
    String tokenHash = "hash";
    AppUser activeUser = new AppUser();
    activeUser.setStatus("ACTIVE");

    RefreshToken reusedToken = new RefreshToken();
    reusedToken.setUser(activeUser);
    reusedToken.setFamilyId(REFRESH_TOKEN_FAMILY_ID);
    reusedToken.markRotatedOutBy(
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

    when(tokenHashingService.sha256Hex(plaintextToken)).thenReturn(tokenHash);
    when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(reusedToken));

    assertThatThrownBy(() -> service.rotate(plaintextToken))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> {
              assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(exception.getReason()).contains("already been used");
            });

    InOrder securityResponseOrder =
        inOrder(refreshTokenRepository, userSessionRepository);
    securityResponseOrder
        .verify(refreshTokenRepository)
        .markFamilyAsTheftSuspected(
            eq(REFRESH_TOKEN_FAMILY_ID), any(OffsetDateTime.class));
    securityResponseOrder
        .verify(userSessionRepository)
        .revokeActiveSessionsByRefreshTokenFamilyId(
            eq(REFRESH_TOKEN_FAMILY_ID), any(OffsetDateTime.class));

    verify(jwtService, never()).issueAccessToken(any(), anyInt(), any());
  }
}
