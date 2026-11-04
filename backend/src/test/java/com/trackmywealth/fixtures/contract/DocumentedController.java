package com.trackmywealth.fixtures.contract;

import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

  @SuccessStatus(HttpStatus.CREATED)
  @Operation(operationId = "createFixture", summary = "Create a fixture")
  @PostMapping("/fixture")
  public ResponseEntity<String> createFixture() {
    return ResponseEntity.status(HttpStatus.CREATED).body("");
  }

  @SuccessStatus(HttpStatus.NO_CONTENT)
  @Operation(operationId = "deleteFixture", summary = "Delete the fixture")
  @DeleteMapping("/fixture")
  public ResponseEntity<Void> deleteFixture() {
    return ResponseEntity.noContent().build();
  }

  @SuccessStatus({HttpStatus.CREATED, HttpStatus.OK})
  @Operation(operationId = "findOrCreateFixture", summary = "Find or create a fixture")
  @PutMapping("/fixture")
  public ResponseEntity<String> findOrCreateFixture(boolean created) {
    return created ? created("") : ResponseEntity.ok("");
  }

  // Not in the contract: springdoc leaves it out, so it needs no @Operation or @SuccessStatus.
  @Hidden
  @PostMapping("/fixture/internal")
  public ResponseEntity<Void> internal() {
    return ResponseEntity.accepted().build();
  }

  private static ResponseEntity<String> created(String body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(body);
  }
}
