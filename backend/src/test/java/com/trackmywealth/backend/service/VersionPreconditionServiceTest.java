package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class VersionPreconditionServiceTest {

  private final VersionPreconditionService service = new VersionPreconditionService();

  @Test
  void matchingVersionAllowsTheMutation() {
    assertThatCode(() -> service.requireCurrent(4, 4, "account")).doesNotThrowAnyException();
  }

  @Test
  void staleVersionReturnsStablePreconditionConflict() {
    assertThatThrownBy(() -> service.requireCurrent(4, 5, "account"))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> {
              assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
              assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT);
              assertThat(ex.getReason()).contains("Reload");
            });
  }

  @Test
  void missingPersistenceVersionIsAnInvariantFailure() {
    assertThatThrownBy(() -> service.requireCurrent(0, null, "account"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no persistence version");
  }
}
