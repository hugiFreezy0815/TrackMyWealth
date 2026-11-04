package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AmortisationScheduleEntryRequest;
import com.trackmywealth.backend.dto.AmortisationScheduleEntryResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AmortisationScheduleService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.SuccessStatus;
import com.trackmywealth.backend.web.VersionedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-10-02/FR-NW-006: a loan's or mortgage's amortisation schedule, one entry per due date. Rules
 * in {@link AmortisationScheduleService}.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/amortisation-schedule")
@Tag(name = "Loans")
public class AmortisationScheduleController {

  private final AmortisationScheduleService amortisationScheduleService;

  public AmortisationScheduleController(AmortisationScheduleService amortisationScheduleService) {
    this.amortisationScheduleService = amortisationScheduleService;
  }

  @Operation(
      operationId = "listAmortisationEntries",
      summary = "List a loan's amortisation schedule")
  @ProblemCodes({ProblemCode.UNPROCESSABLE})
  @GetMapping
  public List<AmortisationScheduleEntryResponse> listEntries(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return amortisationScheduleService.list(accountId, actor);
  }

  @SuccessStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "createAmortisationEntry",
      summary = "Add an amortisation schedule entry")
  @ProblemCodes({ProblemCode.ACCOUNT_ARCHIVED, ProblemCode.UNPROCESSABLE})
  @PostMapping
  public ResponseEntity<AmortisationScheduleEntryResponse> createEntry(
      @PathVariable UUID accountId,
      @Valid @RequestBody AmortisationScheduleEntryRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AmortisationScheduleEntryResponse response =
        amortisationScheduleService.create(accountId, request, actor);
    return VersionedResponse.created(response, response.version());
  }

  @Operation(
      operationId = "replaceAmortisationEntry",
      summary = "Replace an amortisation schedule entry")
  @ProblemCodes({ProblemCode.ACCOUNT_ARCHIVED, ProblemCode.UNPROCESSABLE})
  @PutMapping("/{entryId}")
  public ResponseEntity<AmortisationScheduleEntryResponse> replaceEntry(
      @PathVariable UUID accountId,
      @PathVariable UUID entryId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody AmortisationScheduleEntryRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AmortisationScheduleEntryResponse response =
        amortisationScheduleService.replace(
            accountId, entryId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  @SuccessStatus(HttpStatus.NO_CONTENT)
  @Operation(
      operationId = "deleteAmortisationEntry",
      summary = "Delete an amortisation schedule entry")
  @ProblemCodes({ProblemCode.ACCOUNT_ARCHIVED, ProblemCode.UNPROCESSABLE})
  @DeleteMapping("/{entryId}")
  public ResponseEntity<Void> deleteEntry(
      @PathVariable UUID accountId,
      @PathVariable UUID entryId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    amortisationScheduleService.delete(
        accountId, entryId, IfMatchVersionParser.parse(ifMatch), actor);
    return ResponseEntity.noContent().build();
  }
}
