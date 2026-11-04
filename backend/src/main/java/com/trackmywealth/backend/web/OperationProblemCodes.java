package com.trackmywealth.backend.web;

import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.ApiAccessPaths;
import com.trackmywealth.backend.security.RateLimitFilter;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/**
 * The problem codes an endpoint can answer with (US-29-04, #374): those it declares with {@link
 * ProblemCodes}, plus those its mapping implies. One place for both the OpenAPI contract ({@code
 * ProblemCodeDocumentationConfig}) and the check that keeps the declarations true ({@code
 * ProblemCodeCoverageTest}).
 *
 * <p>Implied are the codes the request handling around the endpoint answers with, whatever the
 * endpoint does: {@code INTERNAL} always; {@code UNAUTHENTICATED} unless the path is public, {@code
 * FORBIDDEN} on an administration path (both from {@link ApiAccessPaths}, which SecurityConfig
 * enforces); {@code VALIDATION_FAILED} when anything is bound from the request; {@code NOT_FOUND}
 * for a path variable; {@code VERSION_REQUIRED} and {@code VERSION_CONFLICT} for {@code If-Match};
 * {@code VERSION_CONFLICT} also for any other write, which can lose a race on a versioned row;
 * {@code INVALID_SORT} for a {@link Paged} list; {@code IMPORT_FILE_TOO_LARGE} for an upload;
 * {@code RATE_LIMITED} where {@code RateLimitFilter} limits the request.
 *
 * <p>Each code is documented under the status this endpoint answers it with ({@link #statusOf}).
 */
public final class OperationProblemCodes {

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  private OperationProblemCodes() {}

  /** Every code the endpoint can answer with: declared and implied. */
  public static Set<ProblemCode> documented(Method method) {
    Set<ProblemCode> codes = EnumSet.noneOf(ProblemCode.class);
    codes.addAll(declared(method));
    codes.addAll(implied(method));
    return Collections.unmodifiableSet(codes);
  }

  /** The codes the endpoint lists in {@link ProblemCodes}; none without the annotation. */
  public static Set<ProblemCode> declared(Method method) {
    ProblemCodes annotation = method.getAnnotation(ProblemCodes.class);
    Set<ProblemCode> codes = EnumSet.noneOf(ProblemCode.class);
    if (annotation != null) {
      Collections.addAll(codes, annotation.value());
    }
    return Collections.unmodifiableSet(codes);
  }

  /** The codes the endpoint's mapping and parameters imply. */
  public static Set<ProblemCode> implied(Method method) {
    Set<ProblemCode> codes = EnumSet.of(ProblemCode.INTERNAL);
    List<String> paths = paths(method);
    if (!paths.stream().allMatch(OperationProblemCodes::isPublic)) {
      codes.add(ProblemCode.UNAUTHENTICATED);
    }
    if (paths.stream().anyMatch(path -> matchesAny(ApiAccessPaths.ADMIN, path))) {
      codes.add(ProblemCode.FORBIDDEN);
    }
    if (isRateLimited(method, paths)) {
      codes.add(ProblemCode.RATE_LIMITED);
    }
    for (Parameter parameter : method.getParameters()) {
      addImpliedBy(parameter, codes);
    }
    // A write can lose a race to a concurrent one on a @Version row (GlobalExceptionHandler's
    // optimistic-locking handler): VERSION_CONFLICT, as a 409 without If-Match (statusOf).
    if (isStateChanging(method)) {
      codes.add(ProblemCode.VERSION_CONFLICT);
    }
    return Collections.unmodifiableSet(codes);
  }

  /**
   * Every code the endpoint can answer with ({@link #documented}), each with the status this
   * endpoint answers it with ({@link #statusOf}), in registry order.
   */
  public static Map<ProblemCode, HttpStatus> documentedWithStatus(Method method) {
    Map<ProblemCode, HttpStatus> codes = new EnumMap<>(ProblemCode.class);
    for (ProblemCode code : documented(method)) {
      codes.put(code, statusOf(method, code));
    }
    return Collections.unmodifiableMap(codes);
  }

  /**
   * The status the endpoint answers {@code code} with: the code's own, except for {@code
   * VERSION_CONFLICT}, which is a 412 where the client sends {@code If-Match} (its precondition
   * failed) and a 409 where it does not (a lost race, RFC 9110; GlobalExceptionHandler).
   */
  public static HttpStatus statusOf(Method method, ProblemCode code) {
    if (code == ProblemCode.VERSION_CONFLICT) {
      return hasIfMatch(method) ? HttpStatus.PRECONDITION_FAILED : HttpStatus.CONFLICT;
    }
    return code.status();
  }

  private static boolean hasIfMatch(Method method) {
    for (Parameter parameter : method.getParameters()) {
      RequestHeader header = parameter.getAnnotation(RequestHeader.class);
      if (header != null && isIfMatch(header)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isStateChanging(Method method) {
    RequestMapping mapping =
        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
    if (mapping == null) {
      return false;
    }
    for (RequestMethod httpMethod : mapping.method()) {
      if (httpMethod == RequestMethod.POST
          || httpMethod == RequestMethod.PUT
          || httpMethod == RequestMethod.PATCH
          || httpMethod == RequestMethod.DELETE) {
        return true;
      }
    }
    return false;
  }

  /** The endpoint's full path patterns: its class's mapping joined with its own. */
  public static List<String> paths(Method method) {
    List<String> paths = new ArrayList<>();
    for (String base :
        pathsOf(
            AnnotatedElementUtils.findMergedAnnotation(
                method.getDeclaringClass(), RequestMapping.class))) {
      for (String own :
          pathsOf(AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class))) {
        paths.add(join(base, own));
      }
    }
    return paths;
  }

  private static void addImpliedBy(Parameter parameter, Set<ProblemCode> codes) {
    RequestHeader header = parameter.getAnnotation(RequestHeader.class);
    if (header != null && isIfMatch(header)) {
      codes.add(ProblemCode.VALIDATION_FAILED);
      codes.add(ProblemCode.VERSION_REQUIRED);
      codes.add(ProblemCode.VERSION_CONFLICT);
      return;
    }
    if (parameter.isAnnotationPresent(PathVariable.class)) {
      codes.add(ProblemCode.VALIDATION_FAILED);
      codes.add(ProblemCode.NOT_FOUND);
    }
    if (parameter.isAnnotationPresent(Paged.class)) {
      codes.add(ProblemCode.VALIDATION_FAILED);
      codes.add(ProblemCode.INVALID_SORT);
    }
    if (parameter.isAnnotationPresent(RequestPart.class)
        || MultipartFile.class.isAssignableFrom(parameter.getType())) {
      codes.add(ProblemCode.VALIDATION_FAILED);
      codes.add(ProblemCode.IMPORT_FILE_TOO_LARGE);
    }
    if (header != null
        || parameter.isAnnotationPresent(RequestParam.class)
        || parameter.isAnnotationPresent(RequestBody.class)
        || parameter.isAnnotationPresent(ModelAttribute.class)) {
      codes.add(ProblemCode.VALIDATION_FAILED);
    }
  }

  private static boolean isRateLimited(Method method, List<String> paths) {
    RequestMapping mapping =
        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
    if (mapping == null) {
      return false;
    }
    for (RequestMethod httpMethod : mapping.method()) {
      for (String path : paths) {
        if (RateLimitFilter.isRateLimited(httpMethod.name(), path)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isIfMatch(RequestHeader header) {
    return IfMatchVersionParser.HEADER.equalsIgnoreCase(header.value())
        || IfMatchVersionParser.HEADER.equalsIgnoreCase(header.name());
  }

  private static boolean isPublic(String path) {
    return matchesAny(ApiAccessPaths.PUBLIC, path);
  }

  private static boolean matchesAny(List<String> patterns, String path) {
    return patterns.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
  }

  private static List<String> pathsOf(RequestMapping mapping) {
    if (mapping == null || mapping.path().length == 0) {
      return List.of("");
    }
    return List.of(mapping.path());
  }

  private static String join(String base, String own) {
    if (own.isEmpty()) {
      return base;
    }
    if (base.endsWith("/") || own.startsWith("/")) {
      return base + own;
    }
    return base + "/" + own;
  }
}
