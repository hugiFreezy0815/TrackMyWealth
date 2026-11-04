package com.trackmywealth.backend.error;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;

/**
 * Renders {@code api/problem-codes.json} (#374): the problem and import row codes as the API
 * contract's breaking-change check ({@code scripts/api_breaking.py}) compares them between {@code
 * main} and a pull request. Removing or renaming a code, changing its statuses, reaction or kind,
 * shrinking a retired list or reusing a retired name is breaking; adding or deprecating a code is
 * not. The OpenAPI contract lists the codes as an open set ({@code x-extensible-enum}), where
 * oasdiff cannot see a removal, so this file is what guards it.
 *
 * <p>Plain JSON with a fixed layout, so the file is byte-identical from one build to the next and
 * the check needs nothing but Python's standard library.
 */
final class ProblemCodeRegistryFile {

  private ProblemCodeRegistryFile() {}

  static String render() {
    StringBuilder json = new StringBuilder("{\n");
    json.append("  \"problemCodes\": {\n");
    ProblemCode[] problems = ProblemCode.values();
    for (int i = 0; i < problems.length; i++) {
      ProblemCode code = problems[i];
      json.append("    ")
          .append(quoted(code.name()))
          .append(": {\"statuses\": [")
          .append(statuses(code))
          .append("], \"reaction\": ")
          .append(quoted(code.reaction().name()))
          .append(", \"deprecated\": ")
          .append(code.isDeprecated())
          .append('}')
          .append(i < problems.length - 1 ? ",\n" : "\n");
    }
    json.append("  },\n");
    json.append("  \"retiredProblemCodes\": [").append(list(ProblemCode.RETIRED)).append("],\n");
    json.append("  \"importRowCodes\": {\n");
    ImportRowCode[] rows = ImportRowCode.values();
    for (int i = 0; i < rows.length; i++) {
      ImportRowCode code = rows[i];
      json.append("    ")
          .append(quoted(code.name()))
          .append(": {\"kind\": ")
          .append(quoted(code.kind().name()))
          .append(", \"deprecated\": ")
          .append(code.isDeprecated())
          .append('}')
          .append(i < rows.length - 1 ? ",\n" : "\n");
    }
    json.append("  },\n");
    json.append("  \"retiredImportRowCodes\": [").append(list(ImportRowCode.RETIRED)).append("]\n");
    return json.append("}\n").toString();
  }

  // The code's own status first, as the catalogue shows it, then the others in registry order.
  private static String statuses(ProblemCode code) {
    return Stream.concat(
            Stream.of(code.status()),
            code.statuses().stream().filter(status -> status != code.status()))
        .map(HttpStatus::value)
        .map(String::valueOf)
        .collect(Collectors.joining(", "));
  }

  private static String list(List<String> names) {
    return names.stream().map(ProblemCodeRegistryFile::quoted).collect(Collectors.joining(", "));
  }

  // Code names are Java identifiers (A-Z, 0-9, _), so they need no escaping; refuse anything else.
  private static String quoted(String name) {
    if (!name.matches("[A-Z][A-Z0-9_]*")) {
      throw new IllegalArgumentException("Not a code name: " + name);
    }
    return '"' + name + '"';
  }
}
