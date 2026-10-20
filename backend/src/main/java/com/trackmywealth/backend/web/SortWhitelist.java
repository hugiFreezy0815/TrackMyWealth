package com.trackmywealth.backend.web;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

/**
 * The sort rules of one {@link Paged} list (#373): which fields a client may sort by, the default
 * order and the unique tie-breaker. Turns the request's {@code sort} values into the entity order,
 * or refuses them with a 400 {@code INVALID_SORT} - an unknown field is never ignored silently.
 */
public final class SortWhitelist {

  private static final String SEPARATOR = ",";
  private static final String ASC = "asc";
  private static final String DESC = "desc";
  private static final int MAX_ECHOED_LENGTH = 60;

  private final Map<String, List<String>> fields;
  private final String defaultOrder;
  private final String tieBreaker;

  private SortWhitelist(Map<String, List<String>> fields, String defaultOrder, String tieBreaker) {
    this.fields = fields;
    this.defaultOrder = defaultOrder;
    this.tieBreaker = tieBreaker;
  }

  /**
   * The rules {@code paged} declares. A declaration that contradicts itself (no field, a field
   * twice, a default order outside the list) is a programming error, not a client's.
   */
  public static SortWhitelist of(Paged paged) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    for (Paged.SortField field : paged.sort()) {
      List<String> properties =
          field.properties().length == 0
              ? List.of(field.name())
              : List.copyOf(Arrays.asList(field.properties()));
      if (field.name().isBlank() || fields.containsKey(field.name())) {
        throw new IllegalStateException(
            "@Paged sort fields must be named and unique: " + field.name());
      }
      fields.put(field.name(), properties);
    }
    if (fields.isEmpty() || paged.tieBreaker().isBlank()) {
      throw new IllegalStateException("@Paged needs at least one sort field and a tie-breaker");
    }
    SortWhitelist whitelist = new SortWhitelist(fields, paged.defaultSort(), paged.tieBreaker());
    try {
      whitelist.resolve(List.of(whitelist.defaultOrder));
    } catch (ApiException e) {
      throw new IllegalStateException(
          "@Paged default sort is not one of its own fields: " + paged.defaultSort(), e);
    }
    return whitelist;
  }

  /** The wire names a client may sort by, in declaration order. */
  public List<String> fieldNames() {
    return List.copyOf(fields.keySet());
  }

  /** The order without a {@code sort} parameter, as a client would write it. */
  public String defaultSort() {
    return defaultOrder;
  }

  /**
   * The entity order for the request's {@code sort} values ({@code null} or none: the default),
   * ending with the tie-breaker.
   *
   * @throws ApiException 400 {@code INVALID_SORT} for an unknown field, a field named twice or a
   *     value that is not exactly {@code <field>}, {@code <field>,asc} or {@code <field>,desc}
   */
  public Sort resolve(List<String> requested) {
    List<String> values =
        requested == null || requested.isEmpty() ? List.of(defaultOrder) : requested;
    List<Sort.Order> orders = new ArrayList<>();
    Set<String> named = new HashSet<>();
    Set<String> ordered = new HashSet<>();
    for (String value : values) {
      String[] parts = value.split(SEPARATOR, -1);
      String field = parts[0];
      Sort.Direction direction = direction(parts, value);
      if (!fields.containsKey(field)) {
        throw invalid(
            "Cannot sort by '%s'. Sort by one of: %s.".formatted(echo(field), allowedFields()));
      }
      if (!named.add(field)) {
        throw invalid("Sort by '%s' only once.".formatted(field));
      }
      for (String property : fields.get(field)) {
        if (ordered.add(property)) {
          orders.add(new Sort.Order(direction, property));
        }
      }
    }
    if (ordered.add(tieBreaker)) {
      orders.add(new Sort.Order(orders.get(0).getDirection(), tieBreaker));
    }
    return Sort.by(orders);
  }

  // Exactly the values the OpenAPI enum lists: lower-case asc/desc, no spaces.
  private Sort.Direction direction(String[] parts, String value) {
    return switch (parts.length) {
      case 1 -> Sort.Direction.ASC;
      case 2 ->
          switch (parts[1]) {
            case ASC -> Sort.Direction.ASC;
            case DESC -> Sort.Direction.DESC;
            default -> throw malformed(value);
          };
      default -> throw malformed(value);
    };
  }

  private ApiException malformed(String value) {
    return invalid(
        "Cannot read sort '%s'. Write sort=<field> or sort=<field>,asc|desc with a field of: %s."
            .formatted(echo(value), allowedFields()));
  }

  private String allowedFields() {
    return String.join(", ", fields.keySet());
  }

  // A client's value goes back in the detail, so a long one is cut short.
  private static String echo(String value) {
    return value.length() <= MAX_ECHOED_LENGTH
        ? value
        : value.substring(0, MAX_ECHOED_LENGTH) + "...";
  }

  private static ApiException invalid(String detail) {
    return new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_SORT, detail);
  }
}
