package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.service.SetupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** US-01-03: the one-time initial-administrator bootstrap. See {@link SetupService}. */
@RestController
public class SetupController {

  private final SetupService setupService;

  public SetupController(SetupService setupService) {
    this.setupService = setupService;
  }

  @PostMapping("/api/v1/setup/administrator")
  public ResponseEntity<AuthTokensResponse> bootstrapAdministrator(
      @Valid @RequestBody SetupAdministratorRequest request,
      @RequestHeader(value = "User-Agent", required = false) String userAgent,
      HttpServletRequest servletRequest) {
    AuthTokensResponse tokens =
        setupService.bootstrapInitialAdministrator(
            request, userAgent, servletRequest.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.CREATED).body(tokens);
  }
}
