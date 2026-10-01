package com.trackmywealth.backend.architecture;

import static com.trackmywealth.backend.architecture.IfMatchExceptions.REVIEWED_WITHOUT_IF_MATCH;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR 0004 / #207 (FR-CNC-001): every mutating endpoint takes {@code If-Match}, so no new
 * read-modify-write endpoint can silently be last-write-wins again.
 *
 * <p>The few that legitimately don't are {@link IfMatchExceptions}, mirroring ADR 0004's "Not
 * read-modify-write" list; any other {@code PUT}/{@code PATCH}/{@code DELETE}/{@code POST} handler
 * without the header fails the build - declared through the shortcut annotations or through
 * {@code @RequestMapping}.
 */
class IfMatchCoverageTest {

  private static final String CONTROLLER_PACKAGE = "com.trackmywealth.backend.controller";

  private static final Set<RequestMethod> MUTATING =
      EnumSet.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

  @Test
  void everyMutatingEndpointTakesIfMatchOrIsAReviewedException() {
    assertThat(mutatingEndpointsWithoutIfMatch())
        .as(
            "mutating endpoints without If-Match that are not listed in REVIEWED_WITHOUT_IF_MATCH"
                + " (and ADR 0004)")
        .isSubsetOf(REVIEWED_WITHOUT_IF_MATCH.keySet());
  }

  @Test
  void everyReviewedExceptionStillExistsAndStillLacksIfMatch() {
    assertThat(mutatingEndpointsWithoutIfMatch())
        .as("stale REVIEWED_WITHOUT_IF_MATCH entries - remove them (and from ADR 0004)")
        .containsAll(REVIEWED_WITHOUT_IF_MATCH.keySet());
  }

  private static Set<String> mutatingEndpointsWithoutIfMatch() {
    Set<String> missing = new TreeSet<>();
    for (JavaClass controller :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(CONTROLLER_PACKAGE)) {
      Class<?> type = controller.reflect();
      if (!type.isAnnotationPresent(RestController.class)) {
        continue;
      }
      for (Method method : type.getDeclaredMethods()) {
        if (isMutating(method) && !takesIfMatch(method)) {
          missing.add(type.getSimpleName() + "#" + method.getName());
        }
      }
    }
    assertThat(missing).as("the scan found the controllers at all").isNotEmpty();
    return missing;
  }

  private static boolean isMutating(Method method) {
    if (method.isAnnotationPresent(PutMapping.class)
        || method.isAnnotationPresent(PatchMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class)
        || method.isAnnotationPresent(PostMapping.class)) {
      return true;
    }
    // The generic form is mutating too: with no method() it answers every HTTP method.
    RequestMapping mapping = method.getAnnotation(RequestMapping.class);
    return mapping != null
        && (mapping.method().length == 0
            || Arrays.stream(mapping.method()).anyMatch(MUTATING::contains));
  }

  private static boolean takesIfMatch(Method method) {
    for (Parameter parameter : method.getParameters()) {
      RequestHeader header = parameter.getAnnotation(RequestHeader.class);
      if (header != null
          && (IfMatchVersionParser.HEADER.equalsIgnoreCase(header.value())
              || IfMatchVersionParser.HEADER.equalsIgnoreCase(header.name()))) {
        return true;
      }
    }
    return false;
  }
}
