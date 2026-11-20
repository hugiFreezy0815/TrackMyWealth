package com.trackmywealth.backend.config;

import com.trackmywealth.backend.web.AppVersion;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.client.*} (application.yml), US-29-16 (#576): the oldest app version this
 * backend still serves. An app that sends an older {@code X-App-Version} is answered 426 {@code
 * APP_UPDATE_REQUIRED} and asks its member to update. Empty (the default) means every version is
 * served. A request without the header is always served: it does not come from the app (PO decision
 * on #576).
 *
 * <p>A value that is not {@code MAJOR.MINOR.PATCH} fails the start, so a typo cannot silently turn
 * the check off.
 */
@ConfigurationProperties(prefix = "app.client")
public record ClientVersionProperties(@Nullable String minimumAppVersion) {

  public ClientVersionProperties {
    if (minimumAppVersion != null
        && !minimumAppVersion.isBlank()
        && AppVersion.parse(minimumAppVersion.strip()).isEmpty()) {
      throw new IllegalArgumentException(
          "app.client.minimum-app-version (MINIMUM_APP_VERSION) must be MAJOR.MINOR.PATCH, for"
              + " example 1.4.0, or empty; was \""
              + minimumAppVersion
              + "\"");
    }
  }

  /** The configured minimum, or empty when every app version is served. */
  public Optional<AppVersion> minimum() {
    return minimumAppVersion == null || minimumAppVersion.isBlank()
        ? Optional.empty()
        : AppVersion.parse(minimumAppVersion.strip());
  }
}
