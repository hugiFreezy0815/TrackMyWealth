package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceparentResponseFilterTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

  private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
  private final TraceparentResponseFilter filter =
      new TraceparentResponseFilter(
          beans.getBeanProvider(Tracer.class), beans.getBeanProvider(Propagator.class));
  private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @Test
  void continuesWithoutTracingBeans() throws Exception {
    assertPassesThroughWithoutTrace();
  }

  @Test
  void continuesWhenTracerHasNoActiveSpan() throws Exception {
    beans.addBean("tracer", mock(Tracer.class));
    beans.addBean("propagator", mock(Propagator.class));

    assertPassesThroughWithoutTrace();
  }

  @Test
  void continuesWhenThePropagatorIsMissing() throws Exception {
    registerActiveSpan();

    assertPassesThroughWithoutTrace();
  }

  @Test
  void errorDispatchWithoutSavedContextDoesNotUseAnUnrelatedSpan() throws Exception {
    registerActiveSpan();
    Propagator propagator = mock(Propagator.class);
    beans.addBean("propagator", propagator);
    request.setDispatcherType(DispatcherType.ERROR);
    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());

    assertPassesThroughWithoutTrace();
    verifyNoInteractions(propagator);
  }

  @Test
  void restoresTheServerTraceOnErrorDispatchAndOnlyWritesTraceparent() throws Exception {
    TraceContext context = registerActiveSpan();
    Propagator propagator = mock(Propagator.class);
    beans.addBean("propagator", propagator);
    doAnswer(
            invocation -> {
              HttpServletResponse carrier = invocation.getArgument(1);
              Propagator.Setter<HttpServletResponse> setter = invocation.getArgument(2);
              setter.set(carrier, "tracestate", "vendor=opaque");
              setter.set(carrier, "Traceparent", TRACEPARENT);
              return null;
            })
        .when(propagator)
        .inject(eq(context), any(HttpServletResponse.class), any());

    assertThatExceptionOfType(ServletException.class)
        .isThrownBy(
            () ->
                filter.doFilter(
                    request,
                    response,
                    (req, res) -> {
                      assertThat(MDC.get(TraceparentResponseFilter.TRACE_ID_MDC_KEY))
                          .isEqualTo(TRACE_ID);
                      throw new ServletException("controller error");
                    }));
    assertThat(response.getHeader("traceparent")).isEqualTo(TRACEPARENT);
    assertThat(response.getHeader("tracestate")).isNull();
    assertThat(MDC.get(TraceparentResponseFilter.TRACE_ID_MDC_KEY)).isNull();

    beans.addBean("tracer", mock(Tracer.class));
    request.setDispatcherType(DispatcherType.ERROR);
    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
    MockHttpServletResponse errorResponse = new MockHttpServletResponse();
    filter.doFilter(
        request,
        errorResponse,
        (req, res) ->
            assertThat(MDC.get(TraceparentResponseFilter.TRACE_ID_MDC_KEY)).isEqualTo(TRACE_ID));

    assertThat(errorResponse.getHeader("traceparent")).isEqualTo(TRACEPARENT);
    assertThat(MDC.get(TraceparentResponseFilter.TRACE_ID_MDC_KEY)).isNull();
  }

  private void assertPassesThroughWithoutTrace() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isSameAs(request);
    assertThat(response.getHeader("traceparent")).isNull();
    assertThat(MDC.get(TraceparentResponseFilter.TRACE_ID_MDC_KEY)).isNull();
  }

  private TraceContext registerActiveSpan() {
    TraceContext context = mock(TraceContext.class);
    when(context.traceId()).thenReturn(TRACE_ID);
    Span span = mock(Span.class);
    when(span.context()).thenReturn(context);
    Tracer tracer = mock(Tracer.class);
    when(tracer.currentSpan()).thenReturn(span);
    beans.addBean("tracer", tracer);
    return context;
  }
}
