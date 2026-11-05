package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ReplaceAccountSnapshotRequest;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountSnapshotService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.backend.web.SendsHeaders;
import com.trackmywealth.backend.web.SuccessStatus;
import com.trackmywealth.backend.web.VersionedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.Paged.SortField;
import org.springframework.data.domain.Pageable;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
@Tag(name = "Account values")
public class AccountSnapshotController {

  private final AccountSnapshotService accountSnapshotService;

  public AccountSnapshotController(AccountSnapshotService accountSnapshotService) {
    this.accountSnapshotService = accountSnapshotService;
  }

  @SuccessStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "recordAccountSnapshot",
      summary = "Record a balance or holdings snapshot")
  @ProblemCodes({ProblemCode.CONFLICT, ProblemCode.RETRY, ProblemCode.UNPROCESSABLE})
  @PostMapping
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<AccountSnapshotResponse> recordSnapshot(
      @PathVariable UUID accountId,
      @Valid @RequestBody RecordAccountSnapshotRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSnapshotResponse response = accountSnapshotService.record(accountId, request, actor);
    return VersionedResponse.created(response, response.version());
  }

  @Operation(operationId = "listAccountSnapshots", summary = "List an account's snapshots")
  @GetMapping
  public PageResponse<AccountSnapshotResponse> listSnapshots(
      @PathVariable UUID accountId,
      @Paged(sort = @SortField(name = "snapshotDate"), defaultSort = "snapshotDate,desc")
          Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return PageResponse.of(accountSnapshotService.list(accountId, pageable, actor));
  }

  @Operation(operationId = "getAccountSnapshot", summary = "Read a snapshot")
  @GetMapping("/{snapshotId}")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<AccountSnapshotResponse> getSnapshot(
      @PathVariable UUID accountId,
      @PathVariable UUID snapshotId,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSnapshotResponse response = accountSnapshotService.get(accountId, snapshotId, actor);
    return VersionedResponse.ok(response, response.version());
  }

  @Operation(operationId = "replaceAccountSnapshot", summary = "Replace a snapshot")
  @ProblemCodes({ProblemCode.CONFLICT, ProblemCode.UNPROCESSABLE})
  @PutMapping("/{snapshotId}")
  @SendsHeaders(ResponseHeader.ETAG)
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
