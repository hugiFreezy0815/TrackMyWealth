package com.trackmywealth.fixtures.contract;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-29-02 (#372): a controller as a new one might be written without the contract annotations, for
 * {@code ApiContractRulesTest}. Outside {@code com.trackmywealth.backend}, so the application's
 * component scan never registers it.
 */
@RestController
public class UndocumentedController {

  @GetMapping("/fixture")
  public String withoutOperation() {
    return "";
  }

  @Operation(summary = "Has a summary but no id")
  @PostMapping("/fixture")
  public String withoutOperationId() {
    return "";
  }
}
