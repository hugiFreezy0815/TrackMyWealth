package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ReplaceAccountSnapshotRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountSnapshotService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.VersionedResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    AccountSnapshotResponse response = accountSnapshotService.record(accountId, request, actor);
    return VersionedResponse.created(response, response.version());
  }

  @GetMapping
  public List<AccountSnapshotResponse> listSnapshots(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountSnapshotService.list(accountId, actor);
  }

  @GetMapping("/{snapshotId}")
  public ResponseEntity<AccountSnapshotResponse> getSnapshot(
      @PathVariable UUID accountId,
      @PathVariable UUID snapshotId,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSnapshotResponse response = accountSnapshotService.get(accountId, snapshotId, actor);
    return VersionedResponse.ok(response, response.version());
  }

  @PutMapping("/{snapshotId}")
  public ResponseEntity<AccountSnapshotResponse> replaceSnapshot(
      @PathVariable UUID accountId,
      @PathVariable UUID snapshotId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody ReplaceAccountSnapshotRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSnapshotResponse response =
        accountSnapshotService.replace(
            accountId, snapshotId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }
}
