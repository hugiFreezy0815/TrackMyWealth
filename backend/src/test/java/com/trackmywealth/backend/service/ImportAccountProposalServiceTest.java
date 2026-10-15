package com.trackmywealth.backend.service;

import static com.trackmywealth.backend.service.ImportFileParserServiceTest.mapping;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.ImportAccountIdentifierRule;
import com.trackmywealth.backend.dto.ImportAccountProposalResponse;
import com.trackmywealth.backend.dto.ImportFileAccountIdentifier;
import com.trackmywealth.backend.dto.ImportParseResult;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ImportTemplateParse;
import com.trackmywealth.backend.entity.InstitutionCatalogue;
import com.trackmywealth.backend.repository.ImportFileRepository;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.ImportFileParserServiceTest.Template;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * US-07-11: what a statement suggests for a new account. Files are read by the real parser, so a
 * suggestion is only ever one the parser can make (a template that takes its currency from the
 * account reads none without one).
 */
class ImportAccountProposalServiceTest {

  private static final String IBAN = "DE89370400440532013000";

  private final ImportFileParserService parser =
      new ImportFileParserService(
          new PdfImportReaderService(mock(LocalOcrService.class)), new PdfBalanceCheckService());

  @Test
  void aMaskedCardRuleOrACardOnlyTypeMakesACreditCardAndARefundDoesNot() {
    assertThat(
            ImportAccountProposalService.accountTypeOf(
                new Template()
                    .accountIdentifier(
                        "COLUMN",
                        new ImportAccountIdentifierRule("MASKED_CARD", null, null, "text"))
                    .build()))
        .isEqualTo("CREDIT_CARD");
    assertThat(
            ImportAccountProposalService.accountTypeOf(
                new Template().typeMapping(Map.of("Abrechnung", "SETTLEMENT")).build()))
        .isEqualTo("CREDIT_CARD");
    assertThat(
            ImportAccountProposalService.accountTypeOf(
                new Template()
                    .descriptionPatterns(Map.of("CREDIT_CARD_PURCHASE", "^Kauf"))
                    .build()))
        .isEqualTo("CREDIT_CARD");
    assertThat(
            ImportAccountProposalService.accountTypeOf(
                new Template().typeMapping(Map.of("Gutschrift", "REFUND")).build()))
        .as("a bank account has refunds too")
        .isEqualTo("CASH");
    assertThat(
            ImportAccountProposalService.accountTypeOf(
                new Template()
                    .accountIdentifier(
                        "COLUMN", new ImportAccountIdentifierRule("IBAN", null, null, "text"))
                    .build()))
        .isEqualTo("CASH");
  }

  @Test
  void theCurrencyIsTheOneEveryReadableRowIsIn() {
    ImportTemplateDefinition perRow =
        new Template()
            .currencyMode("PER_ROW")
            .fixedCurrency(null)
            .mapping(mapping("date", "amount").description("text").currency("currency").build())
            .build();

    assertThat(
            currencyOf(
                perRow,
                "date,amount,currency,text\n2031-01-02,-5.00,EUR,A\n2031-01-03,-6.00,EUR,B\n"))
        .isEqualTo("EUR");
    assertThat(
            currencyOf(
                perRow,
                "date,amount,currency,text\n2031-01-02,-5.00,EUR,A\n2031-01-03,-6.00,CHF,B\n"))
        .as("two currencies: none is the account's")
        .isNull();
  }

  @Test
  void aFixedCurrencyTemplatesOwnCountsEvenWhenNoRowCouldBeRead() {
    ImportTemplateDefinition fixed =
        new Template().currencyMode("FIXED").fixedCurrency("CHF").build();

    assertThat(currencyOf(fixed, "date,amount,text\nnot-a-date,x,A\n")).isEqualTo("CHF");
  }

  @Test
  void aTemplateTakingTheCurrencyFromTheAccountSuggestsNone() {
    ImportTemplateDefinition fromAccount =
        new Template().currencyMode("FROM_ACCOUNT").fixedCurrency(null).build();

    assertThat(currencyOf(fromAccount, "date,amount,text\n2031-01-02,-5.00,A\n")).isNull();
  }

  @Test
  void theIdentifierIsShownAsAnAccountsDisplayValueNeverInFull() {
    assertThat(ImportAccountProposalService.masked(new ImportFileAccountIdentifier("IBAN", IBAN)))
        .isEqualTo(AccountIdentifierService.masked(IBAN, "IBAN"))
        .doesNotContain("0532");
    assertThat(
            ImportAccountProposalService.masked(
                new ImportFileAccountIdentifier("MASKED_CARD", "123456******1234")))
        .isEqualTo("123456******1234");
    assertThat(ImportAccountProposalService.masked(null)).isNull();
    assertThat(ImportAccountProposalService.masked(new ImportFileAccountIdentifier("IBAN", null)))
        .isNull();
  }

  @Test
  void aProposalNamesTheTemplatesCatalogueInstitution() {
    ImportTemplateService templates = mock(ImportTemplateService.class);
    InstitutionCatalogueRepository catalogue = mock(InstitutionCatalogueRepository.class);
    AuthenticatedUserPrincipal actor =
        new AuthenticatedUserPrincipal(UUID.randomUUID(), "USER", UUID.randomUUID(), null, "EN");
    UUID templateId = UUID.randomUUID();
    UUID catalogueId = UUID.randomUUID();
    InstitutionCatalogue bank = new InstitutionCatalogue();
    ReflectionTestUtils.setField(bank, "name", "Synthetic Bank");
    when(catalogue.findById(catalogueId)).thenReturn(Optional.of(bank));
    ImportTemplateDefinition template =
        new Template()
            .preamble(1)
            .accountIdentifier(
                "PREAMBLE_LINE", new ImportAccountIdentifierRule("IBAN", "Konto", null, null))
            .build();
    byte[] file =
        ("Konto;" + IBAN + "\ndate,amount,text\n2031-01-02,-5.00,A\n")
            .getBytes(StandardCharsets.UTF_8);
    when(templates.parseWithoutAccount(templateId, file, actor))
        .thenReturn(
            new ImportTemplateParse(catalogueId, template, parser.parse(file, template, null)));

    ImportAccountProposalResponse proposal =
        new ImportAccountProposalService(
                templates,
                mock(ImportBatchService.class),
                mock(ImportFileRepository.class),
                catalogue,
                new NoTransactions())
            .propose(templateId, file, actor);

    assertThat(proposal)
        .isEqualTo(
            new ImportAccountProposalResponse(
                templateId,
                catalogueId,
                "Synthetic Bank",
                "CASH",
                "CHF",
                AccountIdentifierService.masked(IBAN, "IBAN")));
  }

  private String currencyOf(ImportTemplateDefinition template, String csv) {
    ImportParseResult result = parser.parse(csv.getBytes(StandardCharsets.UTF_8), template, null);
    return ImportAccountProposalService.currencyOf(template, result);
  }

  // Runs a TransactionTemplate's callback without a database.
  private static final class NoTransactions extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
