package com.trackmywealth.backend.architecture.fixture;

import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.error.ReconciliationFinalizedException;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Code shapes {@code ProblemCodeCallGraphTest} must follow - one per kind of edge. */
public final class CallGraphFixtures {

  private CallGraphFixtures() {}

  /** A code named inside a lambda body. */
  public static void inLambda(Optional<String> value) {
    value.orElseThrow(() -> new ApiException(ProblemCode.ACCOUNT_ARCHIVED, "Archived."));
  }

  /** A code named in a method reached by a method reference. */
  public static void throughMethodReference(Optional<String> value) {
    value.orElseThrow(CallGraphFixtures::batchState);
  }

  /** A code named in an implementation reached through its interface. */
  public static void throughInterface(Rule rule) {
    rule.check();
  }

  /** A code named in an exception's own constructor. */
  public static void throughExceptionConstructor() {
    throw new ReconciliationFinalizedException("ACCEPTED");
  }

  /** A plain status: its class-level code. */
  public static void plainStatus() {
    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Rule.");
  }

  /** A success status is no error. */
  public static HttpStatus successStatus() {
    return HttpStatus.CREATED;
  }

  /** Through two hops and a supplier. */
  public static void transitive() {
    Supplier<Object> later =
        () -> {
          plainStatus();
          return null;
        };
    later.get();
  }

  /** Validating a status names every class-level code in ProblemCode; none of that counts. */
  public static void validatedStatus() {
    throw new ApiException(HttpStatus.CONFLICT, ProblemCode.VERSION_CONFLICT, "Raced.");
  }

  private static ApiException batchState() {
    return new ApiException(ProblemCode.IMPORT_BATCH_STATE, "State.");
  }

  /** An interface whose implementation names a code. */
  public interface Rule {
    void check();
  }

  /** The implementation. */
  public static final class LockedRule implements Rule {
    @Override
    public void check() {
      throw new ApiException(ProblemCode.IMPORT_TEMPLATE_READ_ONLY, "Read only.");
    }
  }
}
