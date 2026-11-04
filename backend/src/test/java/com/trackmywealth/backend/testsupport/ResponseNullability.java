package com.trackmywealth.backend.testsupport;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * US-29-07 (#451): the components of a response body that are null although the contract says they
 * never are - every component of a backend record not declared JSpecify {@link Nullable}, the rule
 * {@code ResponseFieldContract} documents.
 *
 * <p>Walks what Jackson writes: records, and the elements of collections, arrays, maps and {@code
 * Optional}s inside them. Other objects are not entered; only the backend's own records are
 * checked, a library type's nulls are its own.
 */
public final class ResponseNullability {

  private static final String BACKEND_PACKAGE = "com.trackmywealth.";

  private ResponseNullability() {}

  /** {@code Record.component} for each undeclared null in {@code body}, sorted; empty when none. */
  public static Set<String> undeclaredNulls(@Nullable Object body) {
    Set<String> found = new TreeSet<>();
    walk(body, found);
    return found;
  }

  private static void walk(@Nullable Object value, Set<String> found) {
    if (value instanceof Record record) {
      walkRecord(record, found);
    } else if (value instanceof Iterable<?> elements) {
      elements.forEach(element -> walk(element, found));
    } else if (value instanceof Object[] elements) {
      for (Object element : elements) {
        walk(element, found);
      }
    } else if (value instanceof Map<?, ?> map) {
      map.values().forEach(element -> walk(element, found));
    } else if (value instanceof Optional<?> optional) {
      optional.ifPresent(element -> walk(element, found));
    }
  }

  private static void walkRecord(Record record, Set<String> found) {
    Class<?> type = record.getClass();
    if (!type.getName().startsWith(BACKEND_PACKAGE)) {
      return;
    }
    for (RecordComponent component : type.getRecordComponents()) {
      Object value = valueOf(record, component);
      if (value == null) {
        if (!component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
          found.add(nameOf(type) + "." + component.getName());
        }
      } else {
        walk(value, found);
      }
    }
  }

  /** The record's name as the contract shows it: a nested record with its enclosing type's. */
  private static String nameOf(Class<?> type) {
    String name = type.getName();
    return name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
  }

  private static @Nullable Object valueOf(Record record, RecordComponent component) {
    try {
      component.getAccessor().setAccessible(true);
      return component.getAccessor().invoke(record);
    } catch (IllegalAccessException | InvocationTargetException failed) {
      throw new IllegalStateException(
          "Cannot read " + nameOf(record.getClass()) + "." + component.getName(), failed);
    }
  }
}
