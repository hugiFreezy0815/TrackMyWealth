package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.MfaConfirmRequest;
import com.trackmywealth.backend.dto.MfaDisableRequest;
import com.trackmywealth.backend.dto.MfaEnrollmentRequest;
import com.trackmywealth.backend.dto.MfaEnrollmentResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.MfaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-02-04: the caller's own TOTP MFA enrollment/confirmation/disablement. See {@link MfaService}
 * for the login-time verification step ({@code POST /api/v1/auth/mfa/verify}, {@link
 * AuthController}), which is deliberately separate since it runs before a caller has a token at
 * all.
 */
@RestController
@RequestMapping("/api/v1/users/me/mfa")
public class MfaController {

  // Referenced by RateLimitFilter (FR-AUT-010) so the two can never silently drift apart.
  public static final String CONFIRM_PATH = "/api/v1/users/me/mfa/confirm";

  private final MfaService mfaService;

  public MfaController(MfaService mfaService) {
    this.mfaService = mfaService;
  }

  @PostMapping("/enroll")
  public ResponseEntity<MfaEnrollmentResponse> enroll(
      @Valid @RequestBody MfaEnrollmentRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
    return ResponseEntity.ok(mfaService.enroll(principal.userId(), request.password()));
  }

  @PostMapping("/confirm")
  public ResponseEntity<Void> confirm(
      @Valid @RequestBody MfaConfirmRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
    mfaService.confirm(principal.userId(), request.code());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/disable")
  public ResponseEntity<Void> disable(
      @Valid @RequestBody MfaDisableRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
    mfaService.disable(principal.userId(), request.password(), request.code());
    return ResponseEntity.noContent().build();
  }
}
