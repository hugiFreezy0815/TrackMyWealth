package com.trackmywealth.backend.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.fixtures.contract.DocumentedController;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * US-29-07 (#451): a null in a record component not declared {@code @Nullable} is found wherever it
 * sits in a response body, and named; a declared one passes.
 */
class ResponseNullabilityTest {

  record Line(String label, @Nullable String note) {}

  record Outer(
      String id,
      @Nullable String comment,
      Line line,
      List<Line> lines,
      Map<String, Line> byKey,
      Optional<Line> maybe,
      Line[] array) {}

  @Test
  void aNullInAComponentNotDeclaredNullableIsNamed() {
    assertThat(ResponseNullability.undeclaredNulls(new Line(null, "x")))
        .containsExactly("ResponseNullabilityTest.Line.label");
  }

  @Test
  void aNullInADeclaredComponentPasses() {
    assertThat(ResponseNullability.undeclaredNulls(new Line("a", null))).isEmpty();
  }

  @Test
  void nullsInsideRecordsCollectionsMapsOptionalsAndArraysAreFoundOnceEach() {
    Line bad = new Line(null, null);
    Outer outer =
        new Outer(
            null, null, bad, List.of(bad), Map.of("k", bad), Optional.of(bad), new Line[] {bad});

    assertThat(ResponseNullability.undeclaredNulls(outer))
        .containsExactly("ResponseNullabilityTest.Line.label", "ResponseNullabilityTest.Outer.id");
    assertThat(ResponseNullability.undeclaredNulls(List.of(outer))).hasSize(2);
  }

  @Test
  void aNullComponentIsNotEnteredAndOtherObjectsAreNotWalked() {
    Outer outer = new Outer("1", null, null, List.of(), Map.of(), Optional.empty(), new Line[0]);

    assertThat(ResponseNullability.undeclaredNulls(outer))
        .containsExactly("ResponseNullabilityTest.Outer.line");
    assertThat(ResponseNullability.undeclaredNulls(null)).isEmpty();
    assertThat(ResponseNullability.undeclaredNulls("a string")).isEmpty();
  }

  @Test
  void theAdviceNotesTheFindingForADocumentedOperationAndLeavesTheBodyAlone() throws Exception {
    ResponseNullabilityAdvice advice = new ResponseNullabilityAdvice();
    MethodParameter returnType = returnTypeOf("getFixture");
    MockHttpServletRequest request = new MockHttpServletRequest();
    Line body = new Line(null, null);

    assertThat(advice.supports(returnType, JacksonJsonHttpMessageConverter.class)).isTrue();
    Object written =
        advice.beforeBodyWrite(
            body,
            returnType,
            MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            new ServletServerHttpRequest(request),
            new ServletServerHttpResponse(new MockHttpServletResponse()));

    assertThat(written).isSameAs(body);
    assertThat(request.getAttribute(ResponseNullabilityAdvice.FINDING))
        .asString()
        .startsWith("DocumentedController#getFixture answered null in")
        .contains("[ResponseNullabilityTest.Line.label]")
        .contains("@Nullable");
  }

  @Test
  void theAdviceNotesNothingForACompleteBodyAndSkipsHiddenOperations() throws Exception {
    ResponseNullabilityAdvice advice = new ResponseNullabilityAdvice();
    MockHttpServletRequest request = new MockHttpServletRequest();

    advice.beforeBodyWrite(
        new Line("a", null),
        returnTypeOf("getFixture"),
        MediaType.APPLICATION_JSON,
        JacksonJsonHttpMessageConverter.class,
        new ServletServerHttpRequest(request),
        new ServletServerHttpResponse(new MockHttpServletResponse()));

    assertThat(request.getAttribute(ResponseNullabilityAdvice.FINDING)).isNull();
    assertThat(advice.supports(returnTypeOf("internal"), JacksonJsonHttpMessageConverter.class))
        .isFalse();
  }

  private static MethodParameter returnTypeOf(String name) {
    for (Method method : DocumentedController.class.getDeclaredMethods()) {
      if (method.getName().equals(name)) {
        return new MethodParameter(method, -1);
      }
    }
    throw new IllegalArgumentException(name);
  }
}
