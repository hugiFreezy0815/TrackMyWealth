package com.trackmywealth.fixtures.contract;

import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.backend.web.SendsHeaders;
import com.trackmywealth.backend.web.SuccessStatus;
import com.trackmywealth.backend.web.VersionedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-29-09 (#455): handlers whose {@code @SendsHeaders} does not match the headers they send, for
 * {@code ApiContractRulesTest}. Every other contract rule holds, so only the header rule fails.
 */
@RestController
@Tag(name = "Fixtures")
public class UndeclaredHeadersController {

  private static final URI LOCATION = URI.create("/fixture/1");

  @Operation(operationId = "versionedWithoutHeaders", summary = "Sends an ETag")
  @GetMapping("/headers/versioned")
  public ResponseEntity<String> versionedWithoutHeaders() {
    return VersionedResponse.ok("", 1);
  }

  @Operation(operationId = "eTagInHelperWithoutHeaders", summary = "Sends an ETag from a helper")
  @GetMapping("/headers/helper")
  public ResponseEntity<String> eTagInHelperWithoutHeaders() {
    return withETag(ResponseEntity.ok());
  }

  @SuccessStatus(HttpStatus.CREATED)
  @Operation(operationId = "locationWithoutHeaders", summary = "Sends a Location")
  @PostMapping("/headers/created")
  public ResponseEntity<String> locationWithoutHeaders() {
    return ResponseEntity.created(LOCATION).body("");
  }

  @SuccessStatus(HttpStatus.CREATED)
  @Operation(operationId = "locationFromHttpHeaders", summary = "Sends a Location it built")
  @PostMapping("/headers/built")
  public ResponseEntity<String> locationFromHttpHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setLocation(LOCATION);
    return ResponseEntity.status(HttpStatus.CREATED).headers(headers).body("");
  }

  @SendsHeaders(ResponseHeader.LOCATION)
  @SuccessStatus(HttpStatus.CREATED)
  @Operation(operationId = "declaresLocationNeverSends", summary = "Declares a Location only")
  @PostMapping("/headers/declared")
  public ResponseEntity<String> declaresLocationNeverSendsIt() {
    return ResponseEntity.status(HttpStatus.CREATED).body("");
  }

  @SendsHeaders(ResponseHeader.LOCATION)
  @Operation(operationId = "locationOnOk", summary = "Sends a Location with a 200")
  @PostMapping("/headers/ok")
  public ResponseEntity<String> locationOnOk() {
    return ResponseEntity.ok().location(LOCATION).body("");
  }

  private static ResponseEntity<String> withETag(ResponseEntity.BodyBuilder builder) {
    return builder.eTag("\"1\"").body("");
  }
}
