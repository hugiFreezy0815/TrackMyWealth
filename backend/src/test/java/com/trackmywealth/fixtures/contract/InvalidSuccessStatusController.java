package com.trackmywealth.fixtures.contract;

import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * #457: deliberate contract violations. Outside the application's component scan, so only
 * ArchitectureRulesBiteTest imports these methods; production controllers remain unchanged.
 */
@RestController
public class InvalidSuccessStatusController {

  @SuccessStatus({})
  @Operation(operationId = "emptySuccessStatuses", summary = "Invalid empty status declaration")
  @GetMapping("/fixture/status/empty")
  public ResponseEntity<String> emptyStatus() {
    return ResponseEntity.ok("ok");
  }

  @SuccessStatus(HttpStatus.BAD_REQUEST)
  @Operation(operationId = "nonSuccessStatus", summary = "Invalid error status declaration")
  @GetMapping("/fixture/status/error")
  public ResponseEntity<String> nonSuccessStatus() {
    return ResponseEntity.ok("ok");
  }

  @Operation(operationId = "unseenSuccessStatus", summary = "Status built in another class")
  @GetMapping("/fixture/status/external")
  public ResponseEntity<String> externalStatus() {
    return ExternalResponses.created();
  }

  @SuccessStatus(HttpStatus.OK)
  @Operation(operationId = "unseenDeclaredSuccessStatus", summary = "External response presumed OK")
  @GetMapping("/fixture/status/external-declared")
  public ResponseEntity<String> externalStatusDeclaredOk() {
    return ExternalResponses.ok();
  }

  @Operation(operationId = "mixedSuccessStatus", summary = "One visible and one external status")
  @GetMapping("/fixture/status/mixed")
  public ResponseEntity<String> mixedLocalAndExternal(@RequestParam boolean create) {
    if (create) {
      return ExternalResponses.created();
    }
    return ResponseEntity.ok("existing");
  }

  @Operation(operationId = "implicitSuccessStatus", summary = "Normal implicit OK")
  @GetMapping("/fixture/status/implicit")
  public String simpleImplicitOk() {
    return "ok";
  }

  // A separate class, not a method of the controller: the bytecode rule must not assume 200.
  private static final class ExternalResponses {

    private ExternalResponses() {}

    static ResponseEntity<String> created() {
      return ResponseEntity.status(HttpStatus.CREATED).body("created");
    }

    static ResponseEntity<String> ok() {
      return ResponseEntity.ok("ok");
    }
  }
}
