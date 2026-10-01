package com.trackmywealth.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class IfMatchVersionParserTest {

  @Test
  void parsesStrongNumericEtag() {
    assertThat(IfMatchVersionParser.parse("\"17\"")).isEqualTo(17);
  }

  @Test
  void missingHeaderIsDeferredToTheAuthorizedServiceLayer() {
    assertThat(IfMatchVersionParser.parse(null)).isNull();
    assertThat(IfMatchVersionParser.parse("   ")).isNull();
  }

  @Test
  void weakWildcardAndUnquotedTagsAreRejected() {
    for (String value :
        new String[] {
          "W/\"7\"",
          "*",
          "7",
          "\"abc\"",
          "\"\"",
          "\"-1\"",
          // beyond Integer.MAX_VALUE, and far beyond long
          "\"2147483648\"",
          "\"99999999999999999999999\"",
          // non-ASCII digits (Arabic-Indic three)
          "\"\u0663\""
        }) {
      assertThatThrownBy(() -> IfMatchVersionParser.parse(value))
          .as(value)
          .isInstanceOfSatisfying(
              ApiException.class,
              ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
              });
    }
  }

  @Test
  void theLargestRepresentableVersionIsAccepted() {
    assertThat(IfMatchVersionParser.parse("\"2147483647\"")).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void writesStrongEtag() {
    assertThat(IfMatchVersionParser.toEtag(9)).isEqualTo("\"9\"");
  }
}
