package com.trackmywealth.backend.error;

/** What {@link ProblemCode} and {@link ImportRowCode} share about a code's lifecycle (#374). */
final class Codes {

  private Codes() {}

  /** Whether the enum constant {@code code} is annotated {@link Deprecated}. */
  static boolean isDeprecated(Enum<?> code) {
    try {
      return code.getDeclaringClass().getField(code.name()).isAnnotationPresent(Deprecated.class);
    } catch (NoSuchFieldException e) {
      // Every enum constant is a public field of its enum; this cannot happen.
      throw new IllegalStateException("No field for enum constant " + code, e);
    }
  }
}
