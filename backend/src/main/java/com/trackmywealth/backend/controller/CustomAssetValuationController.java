package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CustomAssetValuationResponse;
import com.trackmywealth.backend.service.CustomAssetValuationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-05-05. */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/valuations")
public class CustomAssetValuationController {

  private final CustomAssetValuationService customAssetValuationService;

  public CustomAssetValuationController(CustomAssetValuationService customAssetValuationService) {
    this.customAssetValuationService = customAssetValuationService;
  }

  @PostMapping
  public ResponseEntity<CustomAssetValuationResponse> recordValuation(
      @PathVariable UUID accountId, @Valid @RequestBody CreateCustomAssetValuationRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(customAssetValuationService.recordValuation(accountId, request));
  }

  @GetMapping
  public List<CustomAssetValuationResponse> listValuations(@PathVariable UUID accountId) {
    return customAssetValuationService.listValuations(accountId);
  }
}
