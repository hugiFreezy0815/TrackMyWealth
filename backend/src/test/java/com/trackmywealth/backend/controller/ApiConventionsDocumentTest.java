package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * US-29-01 (#371): {@code docs/api/conventions.md} is what a client developer reads, so its two
 * operation tables must say what {@code api/openapi.yaml} says. A new paged list, a new sort field
 * or a new plain-array list that is not written into the guide fails here and names the difference.
 */
class ApiConventionsDocumentTest {

  // Maven runs the tests from backend/.
  private static final Path GUIDE = Path.of("..", "docs", "api", "conventions.md");
  private static final Path CONTRACT = Path.of("..", "api", "openapi.yaml");

  private static final Pattern OPERATION = Pattern.compile("`(GET|POST|PUT|DELETE) (/api/v1\\S*)`");
  private static final Pattern CODE = Pattern.compile("`([^`]+)`");
  private static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch");

  @Test
  void theSortingTableListsEveryPagedOperationWithItsSortFields() throws IOException {
    Map<String, Set<String>> documented = new TreeMap<>();
    for (List<String> row : tableAfter("| Paged operation |")) {
      Set<String> fields = new TreeSet<>();
      // "**None: fixed order**" has no code span, so it names no field.
      Matcher field = CODE.matcher(row.get(1));
      while (field.find()) {
        fields.add(field.group(1));
      }
      documented.put(operationIn(row.get(0)), fields);
    }

    assertThat(documented)
        .as("the Sorting table of %s against the paged operations of %s", GUIDE, CONTRACT)
        .isEqualTo(pagedOperationsOfTheContract());
  }

  @Test
  void theExceptionTableListsEveryOperationThatAnswersAPlainArray() throws IOException {
    Set<String> documented = new TreeSet<>();
    for (List<String> row : tableAfter("| Operation | Current scope or limit |")) {
      documented.add(operationIn(row.get(0)));
    }

    assertThat(documented)
        .as("the array table of %s against the array responses of %s", GUIDE, CONTRACT)
        .isEqualTo(arrayOperationsOfTheContract());
  }

  /** The cells of each body row of the table whose header line starts with {@code header}. */
  private static List<List<String>> tableAfter(String header) throws IOException {
    List<String> lines = Files.readAllLines(GUIDE, StandardCharsets.UTF_8);
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith(header)) {
        start = i;
        break;
      }
    }
    assertThat(start).as("%s has a table starting with \"%s\"", GUIDE, header).isNotNegative();
    // Skip the header and its separator line.
    return lines.stream()
        .skip(start + 2L)
        .takeWhile(line -> line.startsWith("|"))
        .map(line -> List.of(line.substring(1).split("\\|")))
        .toList();
  }

  private static String operationIn(String cell) {
    Matcher operation = OPERATION.matcher(cell);
    assertThat(operation.find()).as("an operation in the table cell \"%s\"", cell).isTrue();
    return operation.group(1) + " " + operation.group(2);
  }

  /** Every operation with a {@code page} parameter, with the fields its {@code sort} allows. */
  private static Map<String, Set<String>> pagedOperationsOfTheContract() throws IOException {
    Map<String, Set<String>> paged = new TreeMap<>();
    forEachOperation(
        (name, operation) -> {
          Map<String, Map<String, Object>> parameters = queryParameters(operation);
          if (parameters.containsKey("page")) {
            paged.put(name, sortFields(parameters.get("sort")));
          }
        });
    return paged;
  }

  /** Every operation whose 2xx JSON response is an array, not an object. */
  private static Set<String> arrayOperationsOfTheContract() throws IOException {
    Set<String> arrays = new TreeSet<>();
    forEachOperation(
        (name, operation) -> {
          for (Map.Entry<String, Object> response : map(operation.get("responses")).entrySet()) {
            Map<String, Object> json =
                map(map(map(response.getValue()).get("content")).get("application/json"));
            if (response.getKey().startsWith("2")
                && "array".equals(map(json.get("schema")).get("type"))) {
              arrays.add(name);
            }
          }
        });
    return arrays;
  }

  private static Set<String> sortFields(Map<String, Object> sort) {
    Set<String> fields = new TreeSet<>();
    if (sort != null) {
      Object values = map(map(sort.get("schema")).get("items")).get("enum");
      for (Object value : values instanceof List<?> list ? list : List.of()) {
        // "bookingDate", "bookingDate,asc" and "bookingDate,desc" are one field.
        fields.add(value.toString().split(",")[0]);
      }
    }
    return fields;
  }

  private static Map<String, Map<String, Object>> queryParameters(Map<String, Object> operation) {
    Map<String, Map<String, Object>> byName = new TreeMap<>();
    Object parameters = operation.get("parameters");
    for (Object parameter : parameters instanceof List<?> list ? list : List.of()) {
      Map<String, Object> declared = map(parameter);
      if ("query".equals(declared.get("in"))) {
        byName.put(declared.get("name").toString(), declared);
      }
    }
    return byName;
  }

  private interface OperationVisitor {
    void visit(String name, Map<String, Object> operation);
  }

  private static void forEachOperation(OperationVisitor visitor) throws IOException {
    Map<String, Object> contract;
    try (Reader reader = Files.newBufferedReader(CONTRACT, StandardCharsets.UTF_8)) {
      contract = map(new Yaml().load(reader));
    }
    for (Map.Entry<String, Object> path : map(contract.get("paths")).entrySet()) {
      for (Map.Entry<String, Object> method : map(path.getValue()).entrySet()) {
        if (METHODS.contains(method.getKey())) {
          visitor.visit(
              method.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey(),
              map(method.getValue()));
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object node) {
    return node instanceof Map<?, ?> ? (Map<String, Object>) node : Map.of();
  }
}
