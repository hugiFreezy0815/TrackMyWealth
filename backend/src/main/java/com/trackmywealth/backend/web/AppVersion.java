package com.trackmywealth.backend.web;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * US-29-16 (#576): the version an app build sends in {@link #HEADER}, {@code MAJOR.MINOR.PATCH} as
 * in the app's {@code app.json}. Only plain numbers: the app's version has no pre-release or build
 * part, and a strict form keeps the comparison with the configured minimum unambiguous.
 */
public record AppVersion(int major, int minor, int patch) {

  /** The request header the app sends its version in. */
  public static final String HEADER = "X-App-Version";

  /** The accepted form; at most six digits per part, so every part fits an int. */
  public static final String PATTERN =
      "^(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})$";

  private static final Pattern FORM = Pattern.compile(PATTERN);

  private static final Comparator<AppVersion> ORDER =
      Comparator.comparingInt(AppVersion::major)
          .thenComparingInt(AppVersion::minor)
          .thenComparingInt(AppVersion::patch);

  /** {@code value} as a version, or empty when it is missing or not {@code MAJOR.MINOR.PATCH}. */
  public static Optional<AppVersion> parse(@Nullable String value) {
    if (value == null) {
      return Optional.empty();
    }
    Matcher matcher = FORM.matcher(value);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(
        new AppVersion(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3))));
  }

  /** Whether this version is older than {@code minimum}, comparing the parts as numbers. */
  public boolean isBefore(AppVersion minimum) {
    return ORDER.compare(this, minimum) < 0;
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}
