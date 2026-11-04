package com.trackmywealth.backend.testsupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * US-29-02 (#372): in every test application context ({@link DocumentedSuccessStatusCheck}), a
 * documented operation that answers a success status {@code api/openapi.yaml} does not list for it
 * fails the request: the caller gets a 500 naming the handler, the status and the documented ones,
 * in place of the real response, so the test making the request fails.
 *
 * <p>The runtime half of {@code ArchitectureTest}'s {@code
 * api_operations_answer_their_documented_success_statuses}, which reads statuses from the bytecode
 * and cannot see one computed at runtime or built in another class. This sees the status actually
 * sent, for every request any test makes - including a handler declaring
 * {@code @SuccessStatus(201)} that answers 200.
 *
 * <p>The response body is held back until the check has passed (nothing is committed before), then
 * written unchanged, with a {@code Content-Length}. No API handler streams or answers
 * asynchronously; one that does fails here loudly rather than losing its body.
 */
public class DocumentedSuccessStatusFilter extends OncePerRequestFilter implements Ordered {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentedSuccessStatusFilter.class);

  @Override
  public int getOrder() {
    // Outermost, so no other filter commits the response before the check.
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    ContentCachingResponseWrapper held = new ContentCachingResponseWrapper(response);
    chain.doFilter(request, held);
    if (request.isAsyncStarted()) {
      // The body would be written into the held wrapper after this returns, and lost.
      throw new IllegalStateException(
          "DocumentedSuccessStatusFilter cannot check an asynchronous or streaming response ("
              + request.getRequestURI()
              + "): teach it to, before such a handler is added");
    }
    Optional<String> violation =
        violation(request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE), held);
    if (violation.isEmpty()) {
      held.copyBodyToResponse();
      return;
    }
    LOG.error(violation.get());
    // Nothing was committed: the handler wrote only into the held wrapper.
    response.reset();
    response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
    response.setContentType(MediaType.TEXT_PLAIN_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(violation.get());
  }

  /** The finding for a handler that answered {@code response}'s status; empty when documented. */
  static Optional<String> violation(Object handler, HttpServletResponse response) {
    HttpStatus status = HttpStatus.resolve(response.getStatus());
    if (!(handler instanceof HandlerMethod method)
        || status == null
        || !status.is2xxSuccessful()
        || !DocumentedHandlers.isDocumentedOperation(method.getBeanType(), method.getMethod())) {
      return Optional.empty();
    }
    Set<HttpStatus> documented = DocumentedHandlers.documentedSuccessStatuses(method.getMethod());
    if (documented.contains(status)) {
      return Optional.empty();
    }
    return Optional.of(
        ("%s#%s answered %s, but api/openapi.yaml documents %s for it (#372): declare the"
                + " statuses it answers with @SuccessStatus, then regenerate the contract.")
            .formatted(
                method.getBeanType().getSimpleName(),
                method.getMethod().getName(),
                status,
                documented));
  }
}
