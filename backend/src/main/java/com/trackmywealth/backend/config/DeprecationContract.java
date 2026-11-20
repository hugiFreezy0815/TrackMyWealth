package com.trackmywealth.backend.config;

import com.trackmywealth.backend.web.ApiDeprecation;
import com.trackmywealth.backend.web.DeprecationHeaders;
import com.trackmywealth.backend.web.Deprecations;
import com.trackmywealth.backend.web.Deprecations.Deprecation;
import io.swagger.v3.core.util.AnnotationsUtils;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;

/**
 * US-29-16 (#576): {@link ApiDeprecation} in the contract. A deprecated operation is {@code
 * deprecated: true} and says since when and where the migration note is; a deprecated field's
 * property is {@code deprecated: true} with the same note. Every success response of an operation a
 * deprecation affects documents the headers {@link DeprecationHeaders} sends: {@code Deprecation}
 * and {@code Link}, and {@code Sunset} once a removal date is committed. Error responses refer to
 * the shared problem response and so cannot list them, but carry them as well.
 */
final class DeprecationContract {

  private DeprecationContract() {}

  /** Documents {@code deprecation} on {@code operation} and its success responses. */
  static void document(Operation operation, Deprecation deprecation) {
    if (deprecation.operation()) {
      operation.setDeprecated(true);
      operation.setDescription(append(operation.getDescription(), note(deprecation)));
    }
    if (operation.getResponses() == null) {
      return;
    }
    operation
        .getResponses()
        .forEach(
            (code, response) -> {
              // A $ref is the shared problem response, which cannot list them.
              if (response.get$ref() == null && code.startsWith("2")) {
                documentHeaders(response, deprecation);
              }
            });
  }

  /**
   * Marks each property whose record component is {@link ApiDeprecation}, reading the record marks
   * of {@link RecordSchemaMarker} without taking them. The property gets its own copy first: a
   * property schema can be shared, as the one decimal schema is by every decimal property.
   */
  static void markFields(OpenAPI openApi) {
    if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
      return;
    }
    for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
      Map<String, Object> extensions = schema.getExtensions();
      Object mark = extensions == null ? null : extensions.get(RecordSchemaMarker.RECORD_EXTENSION);
      // RecordSchemaMarker marks records only.
      if (mark instanceof Class<?> record && schema.getProperties() != null) {
        markFields(schema.getProperties(), record);
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void markFields(Map<String, Schema> properties, Class<?> record) {
    for (RecordComponent component : record.getRecordComponents()) {
      Schema<?> property = properties.get(component.getName());
      if (property == null) {
        continue;
      }
      Deprecations.of(component)
          .ifPresent(
              annotation -> {
                Schema own = AnnotationsUtils.clone(property, true);
                own.setDeprecated(true);
                own.setDescription(append(own.getDescription(), note(annotation)));
                properties.put(component.getName(), own);
              });
    }
  }

  private static void documentHeaders(ApiResponse response, Deprecation deprecation) {
    Map<String, Header> own =
        response.getHeaders() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(response.getHeaders());
    own.put(
        DeprecationHeaders.DEPRECATION,
        new Header()
            .required(true)
            .description(
                "RFC 9745: deprecated since "
                    + deprecation.since()
                    + " ("
                    + DeprecationHeaders.deprecationValue(deprecation.since())
                    + "). Migrate before the Sunset.")
            .schema(new StringSchema().pattern("^@-?[0-9]+$")));
    own.put(
        HttpHeaders.LINK,
        new Header()
            .required(true)
            .description(
                "The migration note: "
                    + deprecation.migrations().stream()
                        .map(DeprecationHeaders::linkValue)
                        .collect(Collectors.joining(", ")))
            .schema(new StringSchema()));
    LocalDate sunset = deprecation.sunset();
    if (sunset != null) {
      own.put(
          DeprecationHeaders.SUNSET,
          new Header()
              .required(true)
              .description(
                  "RFC 8594: removed from "
                      + DeprecationHeaders.sunsetValue(sunset)
                      + " at the earliest.")
              .schema(new StringSchema()));
    }
    response.setHeaders(own);
  }

  private static String note(Deprecation deprecation) {
    return "Deprecated since "
        + deprecation.since()
        + (deprecation.sunset() == null ? "" : ", removed from " + deprecation.sunset())
        + ". Migration: "
        + deprecation.migrations().stream().map(URI::toString).collect(Collectors.joining(", "))
        + ".";
  }

  private static String note(ApiDeprecation annotation) {
    return "Deprecated since "
        + annotation.since()
        + (annotation.sunset().isBlank() ? "" : ", removed from " + annotation.sunset())
        + ". Migration: "
        + annotation.migration()
        + ".";
  }

  private static String append(String description, String note) {
    return description == null || description.isBlank() ? note : description + " " + note;
  }
}
