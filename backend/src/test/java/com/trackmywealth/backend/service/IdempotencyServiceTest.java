package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The pure parts of {@link IdempotencyService} (#574); the database flow is integration-tested. */
class IdempotencyServiceTest {

  private static final UUID ACCOUNT = UUID.fromString("3f2b8c1e-6a4d-4f8e-9b7c-1d2e3f4a5b6c");
  private static final byte[] FILE =
      "Date;Amount\n2026-01-05;1.00\n".getBytes(StandardCharsets.UTF_8);

  @Test
  void aUuidKeyIsStoredInLowerCase() {
    assertThat(IdempotencyService.normalizedKey(" 7C9E6679-7425-40DE-944B-E07FC1F90AE7 "))
        .isEqualTo("7c9e6679-7425-40de-944b-e07fc1f90ae7");
  }

  @Test
  void anythingButAUuidIsAValidationFailure() {
    for (String key : new String[] {"", "retry-1", "1-1-1-1-1", "x".repeat(300), null}) {
      assertThatThrownBy(() -> IdempotencyService.normalizedKey(key))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.getCode().name()).isEqualTo("VALIDATION_FAILED"));
    }
  }

  @Test
  void theFingerprintIsStableAndCoversEveryPartAndTheContent() {
    String fingerprint = fingerprint(FILE, ACCOUNT, null, "statement.csv");

    assertThat(fingerprint).matches("[0-9a-f]{64}");
    assertThat(fingerprint(FILE, ACCOUNT, null, "statement.csv")).isEqualTo(fingerprint);
    assertThat(fingerprint(FILE, ACCOUNT, LocalDate.of(2026, 1, 5), "statement.csv"))
        .isNotEqualTo(fingerprint);
    assertThat(fingerprint(FILE, ACCOUNT, null, "other.csv")).isNotEqualTo(fingerprint);
    assertThat(fingerprint("x".getBytes(StandardCharsets.UTF_8), ACCOUNT, null, "statement.csv"))
        .isNotEqualTo(fingerprint);
  }

  @Test
  void aMissingPartDiffersFromEveryValueAndPartsDoNotRunTogether() {
    assertThat(fingerprint(FILE, (Object) null)).isNotEqualTo(fingerprint(FILE, "null"));
    assertThat(fingerprint(FILE, (Object) null)).isNotEqualTo(fingerprint(FILE, ""));
    assertThat(fingerprint(FILE, "ab", "c")).isNotEqualTo(fingerprint(FILE, "a", "bc"));
  }

  private static String fingerprint(byte[] content, Object... parts) {
    return IdempotencyService.fingerprint(content, parts);
  }
}
