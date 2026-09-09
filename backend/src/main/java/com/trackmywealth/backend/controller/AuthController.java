package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.RefreshTokenRequest;
import com.trackmywealth.backend.service.LoginService;
import com.trackmywealth.backend.service.TokenRotationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-02-02: login and refresh-token rotation. See {@link LoginService}/{@link
 * TokenRotationService}.
 */
@RestController
public class AuthController {

  // Referenced by RateLimitFilter (FR-AUT-010) so the two can never silently drift apart - the
  // mapping annotation below is the single source of truth for both.
  public static final String LOGIN_PATH = "/api/v1/auth/login";
  public static final String REFRESH_PATH = "/api/v1/auth/refresh";

  private final LoginService loginService;
  private final TokenRotationService tokenRotationService;

  public AuthController(LoginService loginService, TokenRotationService tokenRotationService) {
    this.loginService = loginService;
    this.tokenRotationService = tokenRotationService;
  }

  @PostMapping(LOGIN_PATH)
  public ResponseEntity<LoginResponse> login(
      @Valid @RequestBody LoginRequest request,
      @RequestHeader(value = "User-Agent", required = false) String userAgent,
      HttpServletRequest servletRequest) {
    LoginResponse response = loginService.login(request, userAgent, servletRequest.getRemoteAddr());
    return ResponseEntity.ok(response);
  }

  @PostMapping(REFRESH_PATH)
  public ResponseEntity<AuthTokensResponse> refresh(
      @Valid @RequestBody RefreshTokenRequest request) {
    return ResponseEntity.ok(tokenRotationService.rotate(request.refreshToken()));
  }
}
