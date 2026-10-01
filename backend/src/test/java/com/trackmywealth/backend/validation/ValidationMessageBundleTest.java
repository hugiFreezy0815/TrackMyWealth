package com.trackmywealth.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.validation.Constraint;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * #153 / development standards: every Bean Validation message is a message key with an EN and a DE
 * entry, so no response can fall back to hard-coded prose or to Hibernate's own bundle in a
 * language the caller did not ask for. Covers class-level, declaration and type-use constraints
 * ({@code List<@NotNull ...>}) anywhere in the production code.
 */
class ValidationMessageBundleTest {

  private static final String BASE_PACKAGE = "com.trackmywealth.backend";
  private static final Pattern MESSAGE_KEY = Pattern.compile("\\{([^{}]+)}");

  private final Properties english = load("messages.properties");
  private final Properties german = load("messages_de.properties");

  @Test
  void englishAndGermanBundlesHaveTheSameNonBlankKeys() {
    assertThat(german.stringPropertyNames())
        .as("keys in messages_de.properties vs messages.properties")
        .containsExactlyInAnyOrderElementsOf(english.stringPropertyNames());
    assertThat(blankKeys(english)).as("blank EN messages").isEmpty();
    assertThat(blankKeys(german)).as("blank DE messages").isEmpty();
  }

  @Test
  void everyConstraintMessageIsAKeyPresentInBothBundles() {
    Set<String> violations = new TreeSet<>();
    for (JavaClass imported :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE)) {
      Class<?> type = imported.reflect();
      // Class-level constraints (e.g. a cross-field check on a whole request) carry messages too.
      check(type, type, violations);
      for (Field field : type.getDeclaredFields()) {
        check(type, field, violations);
        checkTypeUse(type, field.getAnnotatedType(), violations);
      }
      for (Executable executable : type.getDeclaredMethods()) {
        check(type, executable, violations);
        checkParameters(type, executable, violations);
      }
      for (Executable executable : type.getDeclaredConstructors()) {
        checkParameters(type, executable, violations);
      }
    }
    assertThat(violations)
        .as("constraint messages that are not a {key} in both EN and DE bundles")
        .isEmpty();
  }

  @Test
  void theScanSeesConstraintsAtAll() {
    // Guards the test above against passing vacuously if the scan stopped finding anything.
    Set<String> seen = new TreeSet<>();
    for (JavaClass imported :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE + ".dto")) {
      for (Field field : imported.reflect().getDeclaredFields()) {
        for (Annotation annotation : field.getAnnotations()) {
          if (isConstraint(annotation)) {
            seen.add(annotation.annotationType().getSimpleName());
          }
        }
      }
    }
    assertThat(seen).contains("NotBlank", "Digits", "Pattern", "ValidCurrencyCode");
  }

  private void checkParameters(Class<?> type, Executable executable, Set<String> violations) {
    for (Parameter parameter : executable.getParameters()) {
      check(type, parameter, violations);
      checkTypeUse(type, parameter.getAnnotatedType(), violations);
    }
  }

  private void checkTypeUse(Class<?> type, AnnotatedType annotatedType, Set<String> violations) {
    if (annotatedType instanceof AnnotatedParameterizedType parameterized) {
      for (AnnotatedType argument : parameterized.getAnnotatedActualTypeArguments()) {
        check(type, argument, violations);
        checkTypeUse(type, argument, violations);
      }
    } else if (annotatedType instanceof AnnotatedArrayType array) {
      check(type, array.getAnnotatedGenericComponentType(), violations);
      checkTypeUse(type, array.getAnnotatedGenericComponentType(), violations);
    }
  }

  private void check(Class<?> type, AnnotatedElement element, Set<String> violations) {
    for (Annotation annotation : element.getAnnotations()) {
      if (!isConstraint(annotation)) {
        continue;
      }
      String message = messageOf(annotation);
      Matcher key = MESSAGE_KEY.matcher(message);
      String where =
          type.getSimpleName() + " @" + annotation.annotationType().getSimpleName() + ": ";
      if (!key.matches()) {
        violations.add(where + "hard-coded message \"" + message + "\"");
      } else if (!english.containsKey(key.group(1)) || !german.containsKey(key.group(1))) {
        violations.add(where + "key " + key.group(1) + " missing from a bundle");
      }
    }
  }

  private static boolean isConstraint(Annotation annotation) {
    return annotation.annotationType().isAnnotationPresent(Constraint.class);
  }

  private static String messageOf(Annotation annotation) {
    try {
      return (String) annotation.annotationType().getMethod("message").invoke(annotation);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
      throw new IllegalStateException("constraint without message(): " + annotation, ex);
    }
  }

  private static Set<String> blankKeys(Properties bundle) {
    return bundle.stringPropertyNames().stream()
        .filter(key -> bundle.getProperty(key).isBlank())
        .collect(Collectors.toCollection(TreeSet::new));
  }

  // UTF-8 like spring.messages.encoding; Properties.load(InputStream) would read ISO-8859-1.
  private static Properties load(String resource) {
    Properties properties = new Properties();
    try (InputStream stream =
        ValidationMessageBundleTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(stream).as(resource).isNotNull();
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    } catch (IOException ex) {
      throw new IllegalStateException("could not read " + resource, ex);
    }
    return properties;
  }
}
