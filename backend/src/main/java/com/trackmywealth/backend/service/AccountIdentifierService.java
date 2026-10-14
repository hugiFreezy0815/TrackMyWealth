package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * US-07-10: an account identifier (an IBAN, or a credit card's masked number) is kept only as an
 * HMAC-SHA256 under a key of the account's workspace, derived from the server key {@code
 * app.import.account-identifier-key}, plus a masked display value. The same identifier read from an
 * import file hashes to the same value, so a file finds its account without the number being
 * stored. Never log or persist an identifier, its normalized form or a key.
 *
 * <p>Without a configured key, identification is off: registering an identifier is a 503, and an
 * import's identification says so. A configured key that is not Base64 of at least 32 bytes fails
 * the start. There is deliberately no placeholder key: a public key would let anyone recompute the
 * hashes of every plausible IBAN.
 */
@Service
public class AccountIdentifierService {

  static final int MIN_KEY_BYTES = 32;
  private static final String ALGORITHM = "HmacSHA256";
  private static final int IBAN_CHECK_DIGITS_START = 4;
  private static final int IBAN_MODULUS = 97;
  private static final int LETTER_OFFSET = 10;
  private static final int VISIBLE_LAST_DIGITS = 4;
  private static final char MASK = '*';
  private static final char BULLET = (char) 0x2022;
  private static final String MASKED_PREFIX = String.valueOf(BULLET).repeat(4);
  private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
  // At most the first 6 and the last 4 digits visible (PCI DSS), at least one masked position.
  private static final Pattern MASKED_CARD = Pattern.compile("[0-9]{0,6}\\*+[0-9]{4}");
  private static final int MIN_CARD_LENGTH = 8;
  private static final int MAX_CARD_LENGTH = 19;
  private static final Pattern SEPARATORS = Pattern.compile("[\\s\\u00A0\\u202F-]");
  private static final Pattern MASK_CHARACTERS = Pattern.compile("[X\\u2022\\u00B7#]");

  private static final Pattern BASE64 =
      Pattern.compile("(?:[A-Za-z0-9+/]{4})+(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?");

  private final byte[] key;
  private final AccountRepository accounts;

  public AccountIdentifierService(
      @Value("${app.import.account-identifier-key:}") String configuredKey,
      AccountRepository accounts) {
    this.key = decodeKey(configuredKey);
    this.accounts = accounts;
  }

  static byte[] decodeKey(String configuredKey) {
    if (configuredKey == null || configuredKey.isBlank()) {
      return new byte[0];
    }
    String encoded = configuredKey.strip();
    // Checked up front, so the decoder never throws: its message could quote part of the key.
    if (!BASE64.matcher(encoded).matches()) {
      throw new IllegalStateException("app.import.account-identifier-key must be Base64.");
    }
    byte[] decoded = Base64.getDecoder().decode(encoded);
    if (decoded.length < MIN_KEY_BYTES) {
      throw new IllegalStateException(
          "app.import.account-identifier-key must decode to at least " + MIN_KEY_BYTES + " bytes.");
    }
    return decoded;
  }

  /** Whether a key is configured, so identifiers can be registered and matched. */
  public boolean enabled() {
    return key.length > 0;
  }

  /**
   * The kind of identifier an account is registered with: a credit card's masked number. A credit
   * card is the account with a statement cycle (AccountService's capability flag; only
   * AccountService branches on the account type itself).
   */
  public static String kindOf(Account account) {
    return account.isHasStatementCycle()
        ? ImportTemplateValues.IDENTIFIER_MASKED_CARD
        : ImportTemplateValues.IDENTIFIER_IBAN;
  }

  /**
   * The canonical form of {@code value} as an identifier of {@code kind}, or empty when it is none:
   * an IBAN without separators, upper case and with valid check digits; a masked card number
   * without separators, every mask character as {@code *}, showing at most its first 6 and its last
   * 4 digits.
   */
  public static Optional<String> normalize(String value, String kind) {
    if (value == null) {
      return Optional.empty();
    }
    String compact = SEPARATORS.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
    if (ImportTemplateValues.IDENTIFIER_MASKED_CARD.equals(kind)) {
      String card = MASK_CHARACTERS.matcher(compact).replaceAll(String.valueOf(MASK));
      boolean valid =
          card.length() >= MIN_CARD_LENGTH
              && card.length() <= MAX_CARD_LENGTH
              && MASKED_CARD.matcher(card).matches();
      return valid ? Optional.of(card) : Optional.empty();
    }
    return IBAN.matcher(compact).matches() && ibanChecksumValid(compact)
        ? Optional.of(compact)
        : Optional.empty();
  }

  /** The display value: an IBAN's last 4 characters, a masked card number as it is. */
  public static String masked(String normalized, String kind) {
    if (ImportTemplateValues.IDENTIFIER_MASKED_CARD.equals(kind)) {
      return normalized;
    }
    return MASKED_PREFIX + normalized.substring(normalized.length() - VISIBLE_LAST_DIGITS);
  }

  /**
   * The keyed hash of a normalized identifier in {@code workspaceId}: HMAC-SHA256 under the
   * workspace's key (itself the HMAC of the workspace id under the server key), over the kind and
   * the identifier, as 64 hex digits.
   *
   * @throws ApiException 503 {@code ACCOUNT_IDENTIFICATION_UNAVAILABLE} without a configured key
   */
  public String hash(UUID workspaceId, String normalized, String kind) {
    if (!enabled()) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          ApiErrorCode.ACCOUNT_IDENTIFICATION_UNAVAILABLE,
          "Account identification is not configured on this server.");
    }
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      byte[] workspaceKey = mac.doFinal(workspaceId.toString().getBytes(StandardCharsets.UTF_8));
      mac.init(new SecretKeySpec(workspaceKey, ALGORITHM));
      byte[] digest = mac.doFinal((kind + ":" + normalized).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable.", e);
    }
  }

  /** The active account of the workspace registered with this normalized identifier, if any. */
  public Optional<Account> match(UUID workspaceId, String normalized, String kind) {
    return accounts
        .findByWorkspaceIdAndIdentifierHash(workspaceId, hash(workspaceId, normalized, kind))
        .filter(account -> account.getArchivedAt() == null);
  }

  // ISO 13616: the first four characters moved to the end, letters as 10..35, mod 97 is 1.
  private static boolean ibanChecksumValid(String iban) {
    String rearranged =
        iban.substring(IBAN_CHECK_DIGITS_START) + iban.substring(0, IBAN_CHECK_DIGITS_START);
    int remainder = 0;
    for (int i = 0; i < rearranged.length(); i++) {
      char c = rearranged.charAt(i);
      int value = Character.isDigit(c) ? c - '0' : c - 'A' + LETTER_OFFSET;
      remainder =
          value < LETTER_OFFSET
              ? (remainder * 10 + value) % IBAN_MODULUS
              : (remainder * 100 + value) % IBAN_MODULUS;
    }
    return remainder == 1;
  }
}
