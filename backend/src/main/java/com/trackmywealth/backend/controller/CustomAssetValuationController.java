package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CustomAssetValuationResponse;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.CustomAssetValuationService;
import com.trackmywealth.backend.service.IdempotentCreateService;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.Paged.SortField;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-05-05, US-03-06 (workspace membership checked by {@code AccessControlService}). */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/valuations")
@Tag(name = "Account values")
public class CustomAssetValuationController {

  private final CustomAssetValuationService customAssetValuationService;
  private final IdempotentCreateService idempotentCreates;

  public CustomAssetValuationController(
      CustomAssetValuationService customAssetValuationService,
      IdempotentCreateService idempotentCreates) {
    this.customAssetValuationService = customAssetValuationService;
    this.idempotentCreates = idempotentCreates;
  }

  @SuccessStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "recordAccountValuation",
      summary = "Record a manual valuation of an asset")
  @ProblemCodes({
    ProblemCode.CONFLICT,
    ProblemCode.IDEMPOTENCY_KEY_IN_USE,
    ProblemCode.IDEMPOTENCY_KEY_MISMATCH
  })
  @PostMapping
  public ResponseEntity<CustomAssetValuationResponse> recordValuation(
      @PathVariable UUID accountId,
      @Valid @RequestBody CreateCustomAssetValuationRequest request,
      @Parameter(
              description = IdempotentCreateService.HEADER_DESCRIPTION,
              schema = @Schema(type = "string", format = "uuid"))
          @RequestHeader(value = IdempotentCreateService.HEADER, required = false)
          String idempotencyKey,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            idempotentCreates.create(
                "recordAccountValuation",
                idempotencyKey,
                actor,
                List.of(accountId, request),
                CustomAssetValuationResponse.class,
                () -> customAssetValuationService.recordValuation(accountId, request, actor)));
  }

  @Operation(operationId = "listAccountValuations", summary = "List an asset's manual valuations")
  @GetMapping
  public PageResponse<CustomAssetValuationResponse> listValuations(
      @PathVariable UUID accountId,
      @Paged(sort = @SortField(name = "valuationDate"), defaultSort = "valuationDate,desc")
          Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return PageResponse.of(customAssetValuationService.listValuations(accountId, pageable, actor));
  }
}
