package com.trackmywealth.backend.config;

import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.ResponseHeader;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * US-29-09 (#455): the response headers a handler declares with {@code SendsHeaders}, in the
 * contract. Each is one shared component under {@code components.headers}, referenced from every
 * success response it comes with ({@link ResponseHeader#comesWith}).
 */
final class ResponseHeaderContract {

  private static final String COMPONENT_REF = "#/components/headers/";

  private ResponseHeaderContract() {}

  /** Adds the shared header components the responses refer to. */
  static void addComponents(Components components) {
    for (ResponseHeader header : ResponseHeader.values()) {
      components.addHeaders(header.headerName(), component(header));
    }
  }

  /**
   * Refers each declared header from the success responses it comes with. A response gets its own
   * headers map first: springdoc may share one between responses, and a header added to one must
   * not appear on another.
   */
  static void document(Map<String, ApiResponse> responses, ResponseHeader... declared) {
    responses.forEach(
        (code, response) -> {
          if (response != null) {
            statusOf(code).ifPresent(status -> document(response, status, declared));
          }
        });
  }

  private static void document(
      ApiResponse response, HttpStatus status, ResponseHeader... declared) {
    for (ResponseHeader header : declared) {
      if (header.comesWith(status)) {
        Map<String, Header> own =
            response.getHeaders() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(response.getHeaders());
        own.put(header.headerName(), new Header().$ref(COMPONENT_REF + header.headerName()));
        response.setHeaders(own);
      }
    }
  }

  private static Optional<HttpStatus> statusOf(String code) {
    try {
      return Optional.ofNullable(HttpStatus.resolve(Integer.parseInt(code)));
    } catch (NumberFormatException notAStatus) {
      // "default", or a range such as "2XX": no single status.
      return Optional.empty();
    }
  }

  private static Header component(ResponseHeader header) {
    return switch (header) {
      case ETAG ->
          new Header()
              .required(true)
              .description(
                  "Strong ETag of the returned resource's version, for example \"7\". Send it as"
                      + " "
                      + IfMatchVersionParser.HEADER
                      + " to change or delete this version of the resource.")
              .schema(new StringSchema());
      case LOCATION ->
          new Header()
              .required(true)
              .description("URL of the created resource.")
              .schema(new StringSchema().format("uri-reference"));
    };
  }
}
