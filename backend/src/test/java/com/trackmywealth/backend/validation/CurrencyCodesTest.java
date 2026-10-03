package com.trackmywealth.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The one ISO 4217 check shared by request bodies, query parameters and FX lookups (#224). */
class CurrencyCodesTest {

  @Test
  void onlyAnUpperCaseIso4217CodeIsValid() {
    assertThat(CurrencyCodes.isValid("CHF")).isTrue();
    assertThat(CurrencyCodes.isValid("chf")).isFalse();
    assertThat(CurrencyCodes.isValid("ZZZ")).isFalse();
    assertThat(CurrencyCodes.isValid("")).isFalse();
    assertThat(CurrencyCodes.isValid(null)).isFalse();
  }

  @Test
  void requireValidAnswersBadRequestNamingTheField() {
    assertThat(CurrencyCodes.requireValid("USD", "currency")).isEqualTo("USD");
    assertBadRequest(() -> CurrencyCodes.requireValid("ZZZ", "baseCurrency"), "baseCurrency 'ZZZ'");
    assertBadRequest(() -> CurrencyCodes.requireValid(" ", "currency"), "currency is required");
    assertBadRequest(() -> CurrencyCodes.requireValid(null, "currency"), "currency is required");
  }

  @Test
  void anAbsentOrEmptyRequestedCurrencyIsTheDefault() {
    assertThat(CurrencyCodes.requestedOrDefault(null, "CHF")).isEqualTo("CHF");
    assertThat(CurrencyCodes.requestedOrDefault("", "CHF")).isEqualTo("CHF");
    assertThat(CurrencyCodes.requestedOrDefault("", null)).isNull();
    assertThat(CurrencyCodes.requestedOrDefault("USD", "CHF")).isEqualTo("USD");
    assertBadRequest(() -> CurrencyCodes.requestedOrDefault("usd", "CHF"), "currency 'usd'");
  }

  @Test
  void aStoredDisplayCurrencyMustBeMoneyNotAMetalFundOrTestCode() {
    assertThat(CurrencyCodes.requireMonetary("JPY", "currency")).isEqualTo("JPY");
    assertBadRequest(() -> CurrencyCodes.requireMonetary("XAU", "currency"), "currency 'XAU'");
    assertBadRequest(() -> CurrencyCodes.requireMonetary("XXX", "currency"), "currency 'XXX'");
    assertBadRequest(() -> CurrencyCodes.requireMonetary("ZZZ", "currency"), "ISO 4217");
  }

  @Test
  void aLongRejectedValueIsNotEchoedIntoTheProblemDetail() {
    String payload = "<script>".repeat(100);
    assertThatThrownBy(() -> CurrencyCodes.requireValid(payload, "currency"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e ->
                assertThat(e.getReason())
                    .isEqualTo("currency is not a valid ISO 4217 currency code."));
  }

  private static void assertBadRequest(Runnable call, String reason) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.getReason()).contains(reason);
            });
  }
}
