package com.trackmywealth.backend.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.backend.web.VersionedResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * US-29-02 (#372): the success statuses a handler method answers with, read from its bytecode for
 * {@code ArchitectureTest}'s contract rule.
 *
 * <p>Read from the handler itself, its lambdas (javac compiles them to synthetic {@code
 * lambda$<method>$<n>} methods of the same class) and every method of its own class it calls,
 * transitively - through calls and method references, also into the classes it extends: an {@link
 * HttpStatus} success constant, a {@link ResponseEntity} or {@link VersionedResponse} factory with
 * a fixed status, a {@link ResponseStatus}, or - for a handler not returning a {@code
 * ResponseEntity} - Spring's implicit 200. A status the bytecode cannot tell ({@code
 * ResponseEntity.status(int)}, {@code new ResponseEntity<>(body, int)}, {@code HttpStatus.valueOf})
 * is reported as {@link #unreadable}. {@code ResponseEntity.of(Optional)} and {@code ofNullable}
 * answer 200 (their 404 is not a success).
 *
 * <p>US-29-10 (#457): a status the walk cannot follow because it comes from another class is listed
 * in {@link #externalStatusSources}: a call outside the handler's own classes that returns a {@code
 * ResponseEntity}, a {@code ResponseEntity} builder or an {@link HttpStatusCode}, and a status
 * field declared outside {@link HttpStatus}. {@code DocumentedSuccessStatusFilter} still checks the
 * status actually sent, in every test request.
 *
 * <p>US-29-09 (#455): the same walk collects the {@link ResponseHeader}s the handler {@link
 * #sends}: a {@link VersionedResponse} factory or an {@code eTag(..)} on a response builder sends
 * the {@code ETag}, {@code ResponseEntity.created(uri)} or a {@code location(..)} the {@code
 * Location}, and so do {@link HttpHeaders#setETag} and {@link HttpHeaders#setLocation}. A header
 * set by name ({@code header("ETag", ..)}) is invisible here: javac inlines the name; the runtime
 * check sees it.
 */
record SuccessStatusEvidence(
    Set<HttpStatus> answered,
    List<String> unreadable,
    Set<ResponseHeader> sends,
    List<String> externalStatusSources) {

  private static final Map<String, HttpStatus> STATUS_FACTORIES =
      Map.of(
          ResponseEntity.class.getName() + "#ok", HttpStatus.OK,
          ResponseEntity.class.getName() + "#created", HttpStatus.CREATED,
          ResponseEntity.class.getName() + "#accepted", HttpStatus.ACCEPTED,
          ResponseEntity.class.getName() + "#noContent", HttpStatus.NO_CONTENT,
          VersionedResponse.class.getName() + "#ok", HttpStatus.OK,
          VersionedResponse.class.getName() + "#created", HttpStatus.CREATED);

  // ResponseEntity.of(Optional) / ofNullable(T): 200 with the body, else 404. Only the overloads
  // returning a finished ResponseEntity: of(ProblemDetail) returns an error-response builder.
  private static final Set<String> OPTIONAL_BODY_FACTORIES =
      Set.of(
          ResponseEntity.class.getName() + "#of", ResponseEntity.class.getName() + "#ofNullable");

  // A status computed at runtime: from a number or a name.
  private static final Set<String> UNREADABLE_STATUS_CALLS =
      Set.of(
          HttpStatus.class.getName() + "#valueOf",
          HttpStatus.class.getName() + "#resolve",
          HttpStatusCode.class.getName() + "#valueOf");

  private static final Map<String, ResponseHeader> HEADER_FACTORIES =
      Map.of(
          ResponseEntity.class.getName() + "#created", ResponseHeader.LOCATION,
          VersionedResponse.class.getName() + "#ok", ResponseHeader.ETAG,
          VersionedResponse.class.getName() + "#created", ResponseHeader.ETAG,
          HttpHeaders.class.getName() + "#setETag", ResponseHeader.ETAG,
          HttpHeaders.class.getName() + "#setLocation", ResponseHeader.LOCATION);

  // Methods of ResponseEntity.HeadersBuilder, also when called through a BodyBuilder.
  private static final Map<String, ResponseHeader> HEADER_BUILDER_METHODS =
      Map.of("eTag", ResponseHeader.ETAG, "location", ResponseHeader.LOCATION);

  SuccessStatusEvidence {
    answered = Collections.unmodifiableSet(EnumSet.copyOf(answered));
    unreadable = List.copyOf(unreadable);
    externalStatusSources = List.copyOf(externalStatusSources);
    sends =
        Collections.unmodifiableSet(
            sends.isEmpty() ? EnumSet.noneOf(ResponseHeader.class) : EnumSet.copyOf(sends));
  }

  static SuccessStatusEvidence of(JavaMethod handler) {
    Set<HttpStatus> answered = EnumSet.noneOf(HttpStatus.class);
    List<String> unreadable = new ArrayList<>();
    Set<ResponseHeader> sends = EnumSet.noneOf(ResponseHeader.class);
    List<String> externalStatusSources = new ArrayList<>();
    implicitStatus(handler).ifPresent(answered::add);

    // The handler's class and the ones it inherits from: a helper may live in a base controller.
    Set<JavaClass> own = new HashSet<>(handler.getOwner().getAllRawSuperclasses());
    own.add(handler.getOwner());
    Deque<JavaCodeUnit> pending = new ArrayDeque<>();
    Set<JavaCodeUnit> seen = new HashSet<>();
    enqueueWithLambdas(handler, pending);
    while (!pending.isEmpty()) {
      JavaCodeUnit unit = pending.pop();
      if (!seen.add(unit)) {
        continue;
      }
      unit.getFieldAccesses().stream()
          .filter(access -> access.getTargetOwner().isEquivalentTo(HttpStatus.class))
          .map(access -> successConstant(access.getName()))
          .flatMap(Optional::stream)
          .forEach(answered::add);
      // A status kept in a field (a constant elsewhere, or one of the controller's own): its
      // value is set in an initializer this walk does not read.
      unit.getFieldAccesses().stream()
          .filter(access -> !access.getTargetOwner().isEquivalentTo(HttpStatus.class))
          .filter(access -> isStatus(access.getTarget().getRawType()))
          .forEach(
              access ->
                  externalStatusSources.add(
                      unit.getName()
                          + " reads "
                          + access.getTargetOwner().getName()
                          + "#"
                          + access.getName()));
      // A call, or a method reference (ResponseEntity::ok, this::created): javac compiles the
      // latter to an invokedynamic, which ArchUnit lists apart from the calls.
      List<CodeUnitTarget> targets = new ArrayList<>();
      for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
        targets.add(
            new CodeUnitTarget(
                call.getTargetOwner(),
                call.getName(),
                isStatusFromInt(call),
                call.getTarget().getRawReturnType(),
                call.getTarget().resolveMember()));
      }
      for (JavaMethodReference reference : unit.getMethodReferencesFromSelf()) {
        targets.add(
            new CodeUnitTarget(
                reference.getTargetOwner(),
                reference.getName(),
                false,
                reference.getTarget().getRawReturnType(),
                reference.getTarget().resolveMember()));
      }
      for (CodeUnitTarget target : targets) {
        String name = target.owner().getName() + "#" + target.name();
        sentHeader(target, name).ifPresent(sends::add);
        HttpStatus factoryStatus = factoryStatus(target, name);
        if (factoryStatus != null) {
          answered.add(factoryStatus);
        } else if (UNREADABLE_STATUS_CALLS.contains(name) || target.statusFromInt()) {
          unreadable.add(
              unit.getName() + " calls " + target.owner().getSimpleName() + "#" + target.name());
        } else if (own.contains(target.owner())) {
          target.member().ifPresent(member -> enqueueWithLambdas(member, pending));
        } else if (isExternalStatusSource(target)) {
          // Unseen even when another branch shows a known status: that one proves nothing
          // about this path.
          externalStatusSources.add(unit.getName() + " calls " + name);
        }
      }
      unit.getConstructorCallsFromSelf().stream()
          .filter(call -> call.getTargetOwner().isEquivalentTo(ResponseEntity.class))
          .filter(call -> takesAnInt(call.getTarget().getRawParameterTypes()))
          .forEach(call -> unreadable.add(unit.getName() + " calls new ResponseEntity(..., int)"));
    }
    return new SuccessStatusEvidence(answered, unreadable, sends, externalStatusSources);
  }

  private static HttpStatus factoryStatus(CodeUnitTarget target, String name) {
    if (OPTIONAL_BODY_FACTORIES.contains(name)
        && target.returnType().isEquivalentTo(ResponseEntity.class)) {
      return HttpStatus.OK;
    }
    return STATUS_FACTORIES.get(name);
  }

  /**
   * A call into another class that hands back a response, a response builder or a status: its
   * status is set where this walk does not look. ResponseEntity's own factories and builder methods
   * are exempt - their status is read above, or it completes one already chosen.
   */
  private static boolean isExternalStatusSource(CodeUnitTarget target) {
    if (target.owner().isEquivalentTo(ResponseEntity.class)
        || target.owner().isAssignableTo(ResponseEntity.HeadersBuilder.class)) {
      return false;
    }
    JavaClass returned = target.returnType();
    return returned.isAssignableTo(ResponseEntity.class)
        || returned.isAssignableTo(ResponseEntity.HeadersBuilder.class)
        || isStatus(returned);
  }

  private static boolean isStatus(JavaClass type) {
    return type.isAssignableTo(HttpStatusCode.class);
  }

  private static Optional<ResponseHeader> sentHeader(CodeUnitTarget target, String name) {
    ResponseHeader factory = HEADER_FACTORIES.get(name);
    if (factory != null) {
      return Optional.of(factory);
    }
    if (target.owner().isAssignableTo(ResponseEntity.HeadersBuilder.class)) {
      return Optional.ofNullable(HEADER_BUILDER_METHODS.get(target.name()));
    }
    return Optional.empty();
  }

  private record CodeUnitTarget(
      JavaClass owner,
      String name,
      boolean statusFromInt,
      JavaClass returnType,
      Optional<JavaMethod> member) {}

  // javac compiles a lambda to a synthetic lambda$<enclosing method>$<n> in the same class.
  private static void enqueueWithLambdas(JavaCodeUnit unit, Deque<JavaCodeUnit> pending) {
    pending.push(unit);
    String lambdaPrefix = "lambda$" + unit.getName() + "$";
    unit.getOwner().getMethods().stream()
        .filter(method -> method.getName().startsWith(lambdaPrefix))
        .forEach(pending::push);
  }

  /**
   * The status Spring answers without the handler setting one: none to add for a {@code
   * ResponseEntity}, whose own status wins over any {@code @ResponseStatus}; otherwise the {@code
   * ResponseStatus} of the method or else its class, or 200.
   */
  private static Optional<HttpStatus> implicitStatus(JavaMethod handler) {
    // A plain HttpEntity provides a body and headers but does not set the HTTP status.
    // ResponseEntity is different: its explicit status replaces Spring's implicit 200.
    if (handler.getRawReturnType().isAssignableTo(ResponseEntity.class)) {
      return Optional.empty();
    }
    ResponseStatus declared =
        AnnotatedElementUtils.findMergedAnnotation(handler.reflect(), ResponseStatus.class);
    if (declared == null) {
      declared =
          AnnotatedElementUtils.findMergedAnnotation(
              handler.getOwner().reflect(), ResponseStatus.class);
    }
    return Optional.of(declared == null ? HttpStatus.OK : declared.code());
  }

  private static Optional<HttpStatus> successConstant(String name) {
    for (HttpStatus status : HttpStatus.values()) {
      if (status.name().equals(name) && status.is2xxSuccessful()) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }

  // ResponseEntity.status(int): the number is not readable here.
  private static boolean isStatusFromInt(JavaMethodCall call) {
    return call.getTargetOwner().isEquivalentTo(ResponseEntity.class)
        && "status".equals(call.getName())
        && takesAnInt(call.getTarget().getRawParameterTypes());
  }

  private static boolean takesAnInt(List<JavaClass> parameterTypes) {
    return parameterTypes.stream().anyMatch(type -> type.isEquivalentTo(int.class));
  }
}
