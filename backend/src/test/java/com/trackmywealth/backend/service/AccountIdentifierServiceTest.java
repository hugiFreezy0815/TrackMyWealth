package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccountIdentifierRequest;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * US-07-10: identifiers are normalized, validated and hashed under a workspace key; nothing here
 * ever returns or reports the plain identifier. Identifiers are synthetic (DE89...3000 is the
 * standard published example IBAN).
 */
class AccountIdentifierServiceTest {

  private static final String IBAN = "IBAN";
  private static final String CARD = "MASKED_CARD";
  private static final String CONTRACT = "CONTRACT_NUMBER";
  private static final String KEY =
      Base64.getEncoder()
          .encodeToString(
              "unit-test-only-key-of-more-than-32-bytes".getBytes(StandardCharsets.UTF_8));

  private final AccountIdentifierService service =
      new AccountIdentifierService(KEY, mock(AccountRepository.class));

  @Test
  void ibanHashIsStableIgnoresSpacingAndCaseAndDependsOnTheWorkspace() {
    UUID first = UUID.randomUUID();
    String hash = service.hash(first, normalizedIban("DE89 3704 0044 0532 0130 00"), IBAN);

    assertThat(hash)
        .hasSize(64)
        .matches("[0-9a-f]{64}")
        .isEqualTo(service.hash(first, normalizedIban("de89370400440532013000"), IBAN))
        .isNotEqualTo(
            service.hash(UUID.randomUUID(), normalizedIban("DE89370400440532013000"), IBAN))
        .doesNotContain("370400440532013000");
  }

  @Test
  void anotherServerKeyGivesAnotherHash() {
    UUID workspace = UUID.randomUUID();
    String other =
        Base64.getEncoder()
            .encodeToString(
                "a-different-key-of-more-than-32-bytes!!".getBytes(StandardCharsets.UTF_8));
    AccountIdentifierService rotated =
        new AccountIdentifierService(other, mock(AccountRepository.class));

    String iban = normalizedIban("DE89370400440532013000");
    assertThat(rotated.hash(workspace, iban, IBAN))
        .isNotEqualTo(service.hash(workspace, iban, IBAN));
  }

  @Test
  void anIbanWithWrongCheckDigitsOrShapeIsNone() {
    assertThat(AccountIdentifierService.normalize("DE88370400440532013000", IBAN)).isEmpty();
    assertThat(AccountIdentifierService.normalize("DE89 3704 0044", IBAN)).isEmpty();
    assertThat(AccountIdentifierService.normalize("1234567890123456", IBAN)).isEmpty();
    assertThat(AccountIdentifierService.normalize(null, IBAN)).isEmpty();
    assertThat(AccountIdentifierService.normalize("CH21 0076 2000 0000 0000 0", IBAN))
        .contains("CH2100762000000000000");
  }

  @Test
  void maskedCardsUseOneMaskCharacterSoBanksSpellingsMatch() {
    assertThat(AccountIdentifierService.normalize("1234 56XX XXXX 7890", CARD))
        .contains("123456******7890");
    assertThat(AccountIdentifierService.normalize("1234-56**-****-7890", CARD))
        .contains("123456******7890");
    assertThat(
            AccountIdentifierService.normalize(
                "123456\u2022\u2022\u2022\u2022\u2022\u20227890", CARD))
        .contains("123456******7890");
    assertThat(AccountIdentifierService.normalize("xxxx xxxx xxxx 7890", CARD))
        .contains("************7890");
  }

  /** PCI DSS: at most the first 6 and the last 4 digits; a full number is never accepted. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "1234567890123456",
        "1234567*90123456",
        "1234567**0127890",
        "123456******789",
        "****",
        "123456******7890123"
      })
  void aCardNumberShowingMoreThanSixAndFourDigitsIsNone(String value) {
    assertThat(AccountIdentifierService.normalize(value, CARD)).isEmpty();
  }

  @Test
  void maskedPositionsCountAndCardsAreHashedApartFromIbans() {
    UUID workspace = UUID.randomUUID();
    assertThat(service.hash(workspace, "1234********5678", CARD))
        .isNotEqualTo(service.hash(workspace, "123456******5678", CARD))
        .isNotEqualTo(service.hash(workspace, "1234********5678", IBAN));
  }

  @Test
  void theDisplayValueShowsAnIbansLastFourAndACardAsMasked() {
    assertThat(AccountIdentifierService.masked("DE89370400440532013000", IBAN))
        .isEqualTo("\u2022\u2022\u2022\u20223000");
    assertThat(AccountIdentifierService.masked("123456******7890", CARD))
        .isEqualTo("123456******7890");
  }

  @Test
  void contractNumbersNormalizeAsTenDigitsAndAreHashedSeparately() {
    assertThat(AccountIdentifierService.normalize("4 268 135 009", CONTRACT))
        .contains("4268135009");
    assertThat(AccountIdentifierService.normalize("4-268-135-009", CONTRACT))
        .contains("4268135009");
    assertThat(AccountIdentifierService.normalize("42 681 350 09", CONTRACT))
        .contains("4268135009");
    assertThat(AccountIdentifierService.normalize("4 268 135 00", CONTRACT)).isEmpty();
    assertThat(AccountIdentifierService.normalize("4 268 135 0090", CONTRACT)).isEmpty();
    assertThat(AccountIdentifierService.normalize("4 268 135 00x", CONTRACT)).isEmpty();
    assertThat(AccountIdentifierService.masked("4268135009", CONTRACT))
        .isEqualTo("\u2022\u2022\u2022\u20220009");
    UUID workspace = UUID.randomUUID();
    assertThat(service.hash(workspace, "4268135009", CONTRACT))
        .isNotEqualTo(service.hash(workspace, "4268135009", IBAN))
        .isNotEqualTo(service.hash(UUID.randomUUID(), "4268135009", CONTRACT));
  }

  @Test
  void aRegisteredContractIsMatchedByItsOwnHashWithinTheWorkspace() {
    UUID workspace = UUID.randomUUID();
    String normalized = "4268135009";
    String hashed = service.hash(workspace, normalized, CONTRACT);
    Account account = mock(Account.class);
    AccountRepository repository = mock(AccountRepository.class);
    when(repository.findByWorkspaceIdAndContractIdentifierHash(workspace, hashed))
        .thenReturn(Optional.of(account));
    AccountIdentifierService matching = new AccountIdentifierService(KEY, repository);

    assertThat(matching.match(workspace, normalized, CONTRACT)).contains(account);
    assertThat(matching.match(workspace, normalized, IBAN)).isEmpty();
  }

  @Test
  void withoutAKeyIdentificationIsOffAndHashingIs503() {
    AccountIdentifierService disabled =
        new AccountIdentifierService("", mock(AccountRepository.class));

    assertThat(disabled.enabled()).isFalse();
    assertThatThrownBy(() -> disabled.hash(UUID.randomUUID(), "DE89370400440532013000", IBAN))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatusCode().value()).isEqualTo(503);
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.ACCOUNT_IDENTIFICATION_UNAVAILABLE);
            });
  }

  /** A misconfigured key fails the start, without quoting the key in the message. */
  @Test
  void aMalformedOrShortKeyFailsTheStartWithoutQuotingIt() {
    String notBase64 = "not base64 at all, but long enough to be a key";
    String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> AccountIdentifierService.decodeKey(notBase64))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("base64 at all");
    assertThatThrownBy(() -> AccountIdentifierService.decodeKey(tooShort))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes")
        .hasMessageNotContaining(tooShort);
    assertThat(AccountIdentifierService.decodeKey(KEY)).hasSizeGreaterThanOrEqualTo(32);
  }

  private static String normalizedIban(String value) {
    Optional<String> normalized = AccountIdentifierService.normalize(value, IBAN);
    assertThat(normalized).isPresent();
    return normalized.get();
  }

  @Test
  void aRequestNeverPrintsThePlainIdentifier() {
    assertThat(new AccountIdentifierRequest("DE89 3704 0044 0532 0130 00").toString())
        .doesNotContain("0532")
        .doesNotContain("DE89");
  }
}
