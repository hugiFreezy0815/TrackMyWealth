package com.trackmywealth.backend.architecture;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.trackmywealth.backend.error.ProblemCode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * The application's static call graph, as far as {@code ProblemCodeCoverageTest} needs it
 * (US-29-04, #374): from a code unit, every call, constructor call, method reference and lambda
 * body inside the application, and each implementation a call through an interface or superclass
 * may land in. The codes reached are the {@link ProblemCode} constants used on the way, plus the
 * class-level code of each error {@link HttpStatus} used outside {@code ..error..} (a plain {@code
 * ResponseStatusException}).
 */
final class ProblemCodeCallGraph {

  private static final String APPLICATION = "com.trackmywealth.backend";
  private final JavaClasses classes;
  private final Map<JavaCodeUnit, Set<ProblemCode>> ownCodes = new HashMap<>();

  ProblemCodeCallGraph(JavaClasses classes) {
    this.classes = classes;
  }

  Set<ProblemCode> reachableCodes(JavaCodeUnit start) {
    Set<ProblemCode> codes = EnumSet.noneOf(ProblemCode.class);
    Set<JavaCodeUnit> seen = new HashSet<>();
    Deque<JavaCodeUnit> todo = new ArrayDeque<>(List.of(start));
    while (!todo.isEmpty()) {
      JavaCodeUnit unit = todo.pop();
      if (!seen.add(unit)) {
        continue;
      }
      codes.addAll(ownCodes.computeIfAbsent(unit, ProblemCodeCallGraph::codesUsedIn));
      todo.addAll(successors(unit));
    }
    return codes;
  }

  private static Set<ProblemCode> codesUsedIn(JavaCodeUnit unit) {
    Set<ProblemCode> codes = EnumSet.noneOf(ProblemCode.class);
    boolean inErrorPackage = unit.getOwner().getPackageName().startsWith(APPLICATION + ".error");
    for (JavaFieldAccess access : unit.getFieldAccesses()) {
      JavaClass owner = access.getTargetOwner();
      if (owner.isEquivalentTo(ProblemCode.class) && isConstant(access.getName())) {
        codes.add(ProblemCode.valueOf(access.getName()));
      } else if (!inErrorPackage && owner.isEquivalentTo(HttpStatus.class)) {
        HttpStatus status = HttpStatus.valueOf(access.getName());
        if (status.isError()) {
          codes.add(ProblemCode.forStatus(status));
        }
      }
    }
    return codes;
  }

  private static boolean isConstant(String name) {
    for (ProblemCode code : ProblemCode.values()) {
      if (code.name().equals(name)) {
        return true;
      }
    }
    return false;
  }

  private Collection<JavaCodeUnit> successors(JavaCodeUnit unit) {
    List<JavaCodeUnit> next = new ArrayList<>();
    List<AccessTarget.CodeUnitAccessTarget> targets = new ArrayList<>();
    unit.getCallsFromSelf().forEach(call -> targets.add(call.getTarget()));
    unit.getMethodReferencesFromSelf().forEach(reference -> targets.add(reference.getTarget()));
    unit.getConstructorReferencesFromSelf()
        .forEach(reference -> targets.add(reference.getTarget()));
    for (AccessTarget.CodeUnitAccessTarget target : targets) {
      // The registry's own methods (allows, forStatus) name codes without answering with them.
      if (!target.getOwner().getPackageName().startsWith(APPLICATION)
          || target.getOwner().isEquivalentTo(ProblemCode.class)) {
        continue;
      }
      target.resolveMember().ifPresent(next::add);
      next.addAll(implementationsOf(target));
    }
    // A lambda's body is a synthetic method of the same class (lambda$<method>$<n>); the
    // invokedynamic that creates it is no call ArchUnit records.
    String lambdaPrefix = "lambda$" + unit.getName() + "$";
    for (JavaCodeUnit sibling : unit.getOwner().getCodeUnits()) {
      if (sibling.getName().startsWith(lambdaPrefix)) {
        next.add(sibling);
      }
    }
    return next;
  }

  // A call through an interface or a superclass may land in any implementation that overrides
  // the method.
  private List<JavaCodeUnit> implementationsOf(AccessTarget.CodeUnitAccessTarget target) {
    List<JavaCodeUnit> overrides = new ArrayList<>();
    if (target instanceof AccessTarget.ConstructorCallTarget
        || target instanceof AccessTarget.ConstructorReferenceTarget) {
      return overrides;
    }
    JavaClass owner = target.getOwner();
    for (JavaClass candidate : owner.getAllSubclasses()) {
      if (!classes.contain(candidate.getName())) {
        continue;
      }
      candidate.tryGetMethod(target.getName(), parameterNames(target)).ifPresent(overrides::add);
    }
    return overrides;
  }

  private static String[] parameterNames(AccessTarget.CodeUnitAccessTarget target) {
    return target.getRawParameterTypes().stream().map(JavaClass::getName).toArray(String[]::new);
  }
}
