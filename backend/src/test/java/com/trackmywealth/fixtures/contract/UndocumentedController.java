package com.trackmywealth.fixtures.contract;

import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

  @Operation(operationId = "createWithoutStatus", summary = "Answers 201 without saying so")
  @PostMapping("/fixture/created")
  public ResponseEntity<String> createdWithoutStatus() {
    return ResponseEntity.status(HttpStatus.CREATED).body("");
  }

  @Operation(operationId = "deleteWithoutStatus", summary = "Answers 204 without saying so")
  @DeleteMapping("/fixture")
  public ResponseEntity<Void> deletedWithoutStatus() {
    return ResponseEntity.noContent().build();
  }

  @Operation(operationId = "createInLambda", summary = "Answers 201 from a lambda")
  @PostMapping("/fixture/lambda")
  public ResponseEntity<String> createdInLambda() {
    return Optional.of("")
        .map(body -> ResponseEntity.status(HttpStatus.CREATED).body(body))
        .orElseThrow();
  }

  @Operation(operationId = "createByReference", summary = "Answers 201 by a method reference")
  @PostMapping("/fixture/reference")
  public ResponseEntity<String> createdByReference() {
    return Optional.of("").map(UndocumentedController::created).orElseThrow();
  }

  @Operation(operationId = "createInHelper", summary = "Answers 201 from a helper")
  @PostMapping("/fixture/helper")
  public ResponseEntity<String> createdInHelper() {
    return created("");
  }

  @Operation(operationId = "createFromNumber", summary = "Answers a status from a number")
  @PostMapping("/fixture/number")
  public ResponseEntity<String> statusFromNumber() {
    return ResponseEntity.status(201).body("");
  }

  @SuccessStatus(HttpStatus.CREATED)
  @Operation(operationId = "declaresCreated", summary = "Declares 201 but answers 200")
  @PostMapping("/fixture/declared")
  public ResponseEntity<String> declaresCreatedAnswersOk() {
    return ResponseEntity.ok("");
  }

  private static ResponseEntity<String> created(String body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(body);
  }
}
