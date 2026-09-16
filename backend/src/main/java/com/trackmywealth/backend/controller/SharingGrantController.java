package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.SharingGrantResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SharingGrantService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-03-03. */
@RestController
@RequestMapping("/api/v1/sharing-grants")
public class SharingGrantController {

  private final SharingGrantService sharingGrantService;

  public SharingGrantController(SharingGrantService sharingGrantService) {
    this.sharingGrantService = sharingGrantService;
  }

  @PostMapping
  public ResponseEntity<SharingGrantResponse> grant(
      @Valid @RequestBody CreateSharingGrantRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(sharingGrantService.grant(request, actor));
  }

  @PostMapping("/{grantId}/revoke")
  public SharingGrantResponse revoke(
      @PathVariable UUID grantId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return sharingGrantService.revoke(grantId, actor);
  }
}
