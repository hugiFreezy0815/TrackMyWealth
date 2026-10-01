package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * #197: a client that goes away while its response body is being written - the common case - gets
 * no 500 attempt. Runs a real {@link DispatcherServlet} with {@link GlobalExceptionHandler}, so the
 * failure arrives exactly as Spring delivers it (wrapped by the message converter), not as a
 * hand-built exception passed straight to a handler method.
 */
class ClientDisconnectIntegrationTest {

  private AnnotationConfigWebApplicationContext context;
  private DispatcherServlet servlet;

  @BeforeEach
  void startDispatcher() throws Exception {
    MockServletContext servletContext = new MockServletContext();
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(servletContext);
    context.register(WebConfiguration.class);
    servlet = new DispatcherServlet(context);
    servlet.init(new MockServletConfig(servletContext));
  }

  @AfterEach
  void stopDispatcher() {
    servlet.destroy();
    context.close();
  }

  @Test
  void aClientDisconnectingMidBodyGetsNo500() {
    DisconnectingResponse response = new DisconnectingResponse();

    assertThatCode(() -> servlet.service(new MockHttpServletRequest("GET", "/rows"), response))
        .doesNotThrowAnyException();

    assertThat(response.failedWrites).as("the client really went away mid-body").isPositive();
    assertThat(response.getStatus()).isNotEqualTo(500);
  }

  public record Row(String name, String amount) {}

  @RestController
  public static class RowController {

    // Large enough that the body is still being written when the stream fails.
    @GetMapping("/rows")
    public List<Row> rows() {
      return Collections.nCopies(10_000, new Row("name", "100.50"));
    }
  }

  @Configuration
  @EnableWebMvc
  @Import({RowController.class, GlobalExceptionHandler.class})
  static class WebConfiguration {}

  /** Same simple name as Tomcat's, which is how Spring recognises a disconnect. */
  static class ClientAbortException extends IOException {

    private static final long serialVersionUID = 1L;

    ClientAbortException() {
      super("java.io.IOException: Broken pipe");
    }
  }

  /** A response whose client disconnects after the first kilobyte. */
  static class DisconnectingResponse extends MockHttpServletResponse {

    private int written;
    private int failedWrites;

    @Override
    public ServletOutputStream getOutputStream() {
      return new ServletOutputStream() {
        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
          // blocking writes only
        }

        @Override
        public void write(int b) throws IOException {
          if (++written > 1024) {
            failedWrites++;
            throw new ClientAbortException();
          }
        }
      };
    }
  }
}
