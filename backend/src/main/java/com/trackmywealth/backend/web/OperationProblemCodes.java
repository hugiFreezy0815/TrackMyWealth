package com.trackmywealth.backend.web;

import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.RateLimitFilter;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
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
 * FORBIDDEN} on an administration path ({@code SecurityConfig} uses the same patterns); {@code
 * VALIDATION_FAILED} when anything is bound from the request; {@code NOT_FOUND} for a path
 * variable; {@code VERSION_REQUIRED} and {@code VERSION_CONFLICT} for {@code If-Match}; {@code
 * INVALID_SORT} for a {@link Paged} list; {@code IMPORT_FILE_TOO_LARGE} for an upload; {@code
 * RATE_LIMITED} where {@code RateLimitFilter} limits the request.
 */
public final class OperationProblemCodes {

  /** First-run setup; public (SecurityConfig). */
  public static final String SETUP_PATHS = "/api/v1/setup/**";

  /** Sign-in and token refresh; public (SecurityConfig). */
  public static final String AUTH_PATHS = "/api/v1/auth/**";

  /** Administration; SYSTEM_ADMINISTRATOR only (SecurityConfig). */
  public static final String ADMIN_PATHS = "/api/v1/admin/**";

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
    if (paths.stream().anyMatch(path -> PATH_MATCHER.match(ADMIN_PATHS, path))) {
      codes.add(ProblemCode.FORBIDDEN);
    }
    if (isRateLimited(method, paths)) {
      codes.add(ProblemCode.RATE_LIMITED);
    }
    for (Parameter parameter : method.getParameters()) {
      addImpliedBy(parameter, codes);
    }
    return Collections.unmodifiableSet(codes);
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
    return PATH_MATCHER.match(SETUP_PATHS, path) || PATH_MATCHER.match(AUTH_PATHS, path);
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
