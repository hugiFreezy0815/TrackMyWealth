package com.trackmywealth.fixtures.contract;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-29-02 (#372): a controller that follows the contract rules, for {@code ApiContractRulesTest}.
 */
@RestController
@Tag(name = "Fixtures")
public class DocumentedController {

  @Operation(operationId = "getFixture", summary = "Read the fixture")
  @GetMapping("/fixture")
  public String getFixture() {
    return "";
  }
}
