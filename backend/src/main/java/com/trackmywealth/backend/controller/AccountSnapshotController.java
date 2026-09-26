package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ReplaceAccountSnapshotRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountSnapshotService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-25-01: manual snapshot entry (FR-REC-006). Rules in {@link AccountSnapshotService}. */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/snapshots")
public class AccountSnapshotController {

  private final AccountSnapshotService accountSnapshotService;

  public AccountSnapshotController(AccountSnapshotService accountSnapshotService) {
    this.accountSnapshotService = accountSnapshotService;
  }

  @PostMapping
  public ResponseEntity<AccountSnapshotResponse> recordSnapshot(
      @PathVariable UUID accountId,
      @Valid @RequestBody RecordAccountSnapshotRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(accountSnapshotService.record(accountId, request, actor));
  }

  @GetMapping
  public List<AccountSnapshotResponse> listSnapshots(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountSnapshotService.list(accountId, actor);
  }

  @GetMapping("/{snapshotId}")
  public AccountSnapshotResponse getSnapshot(
      @PathVariable UUID accountId,
      @PathVariable UUID snapshotId,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountSnapshotService.get(accountId, snapshotId, actor);
  }

  @PutMapping("/{snapshotId}")
  public AccountSnapshotResponse replaceSnapshot(
      @PathVariable UUID accountId,
      @PathVariable UUID snapshotId,
      @Valid @RequestBody ReplaceAccountSnapshotRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountSnapshotService.replace(accountId, snapshotId, request, actor);
  }
}
