package com.trackmywealth.fixtures.contract;

import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

  // The helper hands back a builder, not a ResponseEntity: it really answers 202.
  @Operation(operationId = "builderSuccessStatus", summary = "Builder from another class")
  @GetMapping("/fixture/status/builder")
  public ResponseEntity<String> builderFromHelper(@RequestParam boolean queue) {
    if (queue) {
      return ExternalResponses.accepted().body("queued");
    }
    return ResponseEntity.ok("done");
  }

  @Operation(operationId = "computedSuccessStatus", summary = "Status from another class")
  @GetMapping("/fixture/status/computed")
  public ResponseEntity<String> statusFromHelper(@RequestParam boolean queue) {
    if (queue) {
      return ResponseEntity.status(ExternalResponses.queued()).body("queued");
    }
    return ResponseEntity.ok("done");
  }

  @Operation(operationId = "constantSuccessStatus", summary = "Status kept in a field")
  @GetMapping("/fixture/status/constant")
  public ResponseEntity<String> statusFromField(@RequestParam boolean queue) {
    if (queue) {
      return ResponseEntity.status(ExternalResponses.QUEUED).body("queued");
    }
    return ResponseEntity.ok("done");
  }

  // Two handlers of one name: a Controller#method exemption cannot tell them apart.
  @Operation(operationId = "overloadedAll", summary = "Overloaded handler")
  @GetMapping("/fixture/status/overloaded")
  public String overloaded() {
    return "all";
  }

  @Operation(operationId = "overloadedOne", summary = "Overloaded handler")
  @GetMapping("/fixture/status/overloaded/{id}")
  public String overloaded(@PathVariable String id) {
    return id;
  }

  @Operation(operationId = "implicitSuccessStatus", summary = "Normal implicit OK")
  @GetMapping("/fixture/status/implicit")
  public String simpleImplicitOk() {
    return "ok";
  }

  // A separate class, not a method of the controller: the bytecode rule must not assume 200.
  private static final class ExternalResponses {

    static final HttpStatus QUEUED = HttpStatus.ACCEPTED;

    private ExternalResponses() {}

    static ResponseEntity.BodyBuilder accepted() {
      return ResponseEntity.accepted();
    }

    static HttpStatus queued() {
      return HttpStatus.ACCEPTED;
    }

    static ResponseEntity<String> created() {
      return ResponseEntity.status(HttpStatus.CREATED).body("created");
    }

    static ResponseEntity<String> ok() {
      return ResponseEntity.ok("ok");
    }
  }
}
