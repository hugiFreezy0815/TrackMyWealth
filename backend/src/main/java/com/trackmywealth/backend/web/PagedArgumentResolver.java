package com.trackmywealth.backend.web;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * The one place that reads a paged list's {@code page}, {@code size} and {@code sort} (#373, ADR
 * 0006) for a {@link Paged} {@link Pageable} parameter: zero-based pages, a default size of {@value
 * #DEFAULT_SIZE}, sizes above {@value #MAX_SIZE} capped, and the sort checked against the list's
 * whitelist and closed by its unique tie-breaker. A page or size that is no whole number, a
 * negative page or a size below 1 is a 400 {@code VALIDATION_FAILED}.
 *
 * <p>Registered ahead of Spring Data's own {@code Pageable} resolver, which would pass any sort
 * property through.
 */
public class PagedArgumentResolver implements HandlerMethodArgumentResolver {

  /** The page size without a {@code size} parameter. */
  public static final int DEFAULT_SIZE = 50;

  /** The largest page size; a larger {@code size} is capped to it. */
  public static final int MAX_SIZE = 200;

  public static final String PAGE = "page";
  public static final String SIZE = "size";
  public static final String SORT = "sort";

  private final Map<MethodParameter, SortWhitelist> whitelists = new ConcurrentHashMap<>();

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return Pageable.class.equals(parameter.getParameterType())
        && parameter.hasParameterAnnotation(Paged.class);
  }

  @Override
  public Pageable resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    int page = wholeNumber(webRequest.getParameter(PAGE), PAGE, 0, 0);
    int size =
        Math.min(wholeNumber(webRequest.getParameter(SIZE), SIZE, 1, DEFAULT_SIZE), MAX_SIZE);
    String[] sort = webRequest.getParameterValues(SORT);
    SortWhitelist whitelist =
        whitelists.computeIfAbsent(
            parameter,
            // supportsParameter admits only a parameter that carries @Paged.
            key ->
                SortWhitelist.of(Objects.requireNonNull(key.getParameterAnnotation(Paged.class))));
    return PageRequest.of(
        page, size, whitelist.resolve(sort == null ? List.of() : Arrays.asList(sort)));
  }

  private static int wholeNumber(String value, String name, int minimum, int absent) {
    if (value == null) {
      return absent;
    }
    int number;
    try {
      number = Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw notAWholeNumber(name, minimum, e);
    }
    if (number < minimum) {
      throw notAWholeNumber(name, minimum, null);
    }
    return number;
  }

  private static ApiException notAWholeNumber(String name, int minimum, Throwable cause) {
    return new ApiException(
        HttpStatus.BAD_REQUEST,
        ApiErrorCode.VALIDATION_FAILED,
        "%s must be a whole number of at least %d.".formatted(name, minimum),
        cause);
  }
}
