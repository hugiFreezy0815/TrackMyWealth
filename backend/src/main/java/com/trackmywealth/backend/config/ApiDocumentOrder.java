package com.trackmywealth.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * US-29-02 (#372): puts every part of the OpenAPI document whose order springdoc takes from
 * classpath scanning into a fixed order, so the committed {@code api/openapi.yaml} is
 * byte-identical from build to build and a diff shows only real contract changes.
 *
 * <p>Sorted by name: the paths, each operation's responses, every components section and the tag
 * list. Left as they are, because their order is the code's own and carries meaning: the properties
 * of a schema (a record's components, in declaration order) and an operation's parameters (the
 * method's).
 */
final class ApiDocumentOrder {

  private ApiDocumentOrder() {}

  static void apply(OpenAPI openApi) {
    Map<String, PathItem> paths = openApi.getPaths();
    if (paths != null) {
      Map<String, Object> extensions = openApi.getPaths().getExtensions();
      openApi.setPaths(new Paths());
      new TreeMap<>(paths).forEach(openApi.getPaths()::addPathItem);
      openApi.getPaths().setExtensions(extensions);
      openApi
          .getPaths()
          .values()
          .forEach(item -> item.readOperations().forEach(ApiDocumentOrder::sort));
    }
    if (openApi.getTags() != null) {
      openApi.getTags().sort(Comparator.comparing(Tag::getName));
    }
    Components components = openApi.getComponents();
    if (components != null) {
      components.setSchemas(sorted(components.getSchemas()));
      components.setResponses(sorted(components.getResponses()));
      components.setParameters(sorted(components.getParameters()));
      components.setHeaders(sorted(components.getHeaders()));
      components.setRequestBodies(sorted(components.getRequestBodies()));
      components.setSecuritySchemes(sorted(components.getSecuritySchemes()));
      components.setExamples(sorted(components.getExamples()));
    }
  }

  private static void sort(Operation operation) {
    Map<String, ApiResponse> responses = operation.getResponses();
    if (responses != null) {
      Map<String, Object> extensions = operation.getResponses().getExtensions();
      operation.setResponses(new ApiResponses());
      new TreeMap<>(responses).forEach(operation.getResponses()::addApiResponse);
      operation.getResponses().setExtensions(extensions);
    }
  }

  // A missing section stays missing: an empty map would be written as "{}".
  private static <T> Map<String, T> sorted(Map<String, T> section) {
    return section == null ? section : new LinkedHashMap<>(new TreeMap<>(section));
  }
}
