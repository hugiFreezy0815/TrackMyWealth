package com.trackmywealth.backend.testsupport;

import java.lang.reflect.Method;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * US-29-07 (#451): in every test application context ({@link DocumentedSuccessStatusCheck}), looks
 * at the body each documented operation answers before Jackson writes it, and notes every null in a
 * record component the contract declares never null ({@link ResponseNullability}).
 *
 * <p>Only notes it, as the request attribute {@link #FINDING}: {@link
 * DocumentedSuccessStatusFilter}, which holds the response back, turns it into a 500 naming the
 * components, so the test making the request fails - however the test reads the response.
 */
@ControllerAdvice
public class ResponseNullabilityAdvice implements ResponseBodyAdvice<Object> {

  /** The request attribute holding the finding, when there is one. */
  public static final String FINDING = ResponseNullabilityAdvice.class.getName() + ".finding";

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    Method method = returnType.getMethod();
    return method != null
        && DocumentedHandlers.isDocumentedOperation(
            ClassUtils.getUserClass(returnType.getContainingClass()), method);
  }

  @Override
  public @Nullable Object beforeBodyWrite(
      @Nullable Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    Set<String> undeclared = ResponseNullability.undeclaredNulls(body);
    if (!undeclared.isEmpty() && request instanceof ServletServerHttpRequest servlet) {
      Method method = returnType.getMethod();
      servlet
          .getServletRequest()
          .setAttribute(
              FINDING,
              ("%s#%s answered null in %s, which api/openapi.yaml declares never null (#451):"
                      + " mark each such record component @Nullable"
                      + " (org.jspecify.annotations), then regenerate the contract.")
                  .formatted(
                      ClassUtils.getUserClass(returnType.getContainingClass()).getSimpleName(),
                      method == null ? "?" : method.getName(),
                      undeclared));
    }
    return body;
  }
}
