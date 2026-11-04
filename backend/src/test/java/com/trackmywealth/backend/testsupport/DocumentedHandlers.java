package com.trackmywealth.backend.testsupport;

import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * US-29-02 (#372): which handler methods are operations of {@code api/openapi.yaml}, and which
 * success statuses the contract promises for them. Shared by {@code ArchitectureTest}'s contract
 * rules (on the bytecode) and {@link DocumentedSuccessStatusFilter} (on every request a test
 * makes), so both judge exactly the operations springdoc documents.
 *
 * <p>springdoc documents a request-mapped method of a controller bean when it has {@code
 * ResponseBody} on the method or its class ({@code @RestController} carries it) or an {@code
 * Operation} - unless the method or its class is {@code @Hidden}, or its {@code Operation} is
 * hidden. A {@code @Controller} rendering views or redirects is not part of the API.
 */
public final class DocumentedHandlers {

  private DocumentedHandlers() {}

  /** A controller with at least one documented operation: it must name its {@code @Tag}. */
  public static boolean isDocumentedController(Class<?> type) {
    return isController(type)
        && !isHidden(type)
        && Arrays.stream(type.getDeclaredMethods())
            .anyMatch(method -> isDocumentedOperation(type, method));
  }

  /** {@code method}, handling requests for a bean of {@code beanType}, is in the contract. */
  public static boolean isDocumentedOperation(Class<?> beanType, Method method) {
    if (!isController(beanType)
        || isHidden(beanType)
        || method.isSynthetic()
        || !AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)
        || isHidden(method)) {
      return false;
    }
    return AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class)
        || AnnotatedElementUtils.hasAnnotation(beanType, ResponseBody.class)
        || AnnotatedElementUtils.hasAnnotation(method, Operation.class);
  }

  /**
   * The success statuses the contract documents for {@code method}: those of its {@link
   * SuccessStatus}, otherwise springdoc's 200.
   */
  public static Set<HttpStatus> documentedSuccessStatuses(Method method) {
    SuccessStatus declared =
        AnnotatedElementUtils.findMergedAnnotation(method, SuccessStatus.class);
    Set<HttpStatus> statuses = EnumSet.noneOf(HttpStatus.class);
    if (declared == null) {
      statuses.add(HttpStatus.OK);
    } else {
      statuses.addAll(Arrays.asList(declared.value()));
    }
    return Collections.unmodifiableSet(statuses);
  }

  private static boolean isController(Class<?> type) {
    return AnnotatedElementUtils.hasAnnotation(type, Controller.class);
  }

  private static boolean isHidden(Class<?> type) {
    return AnnotatedElementUtils.hasAnnotation(type, Hidden.class);
  }

  private static boolean isHidden(Method method) {
    if (AnnotatedElementUtils.hasAnnotation(method, Hidden.class)) {
      return true;
    }
    Operation operation = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
    return operation != null && operation.hidden();
  }
}
