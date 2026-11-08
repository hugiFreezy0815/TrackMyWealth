package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository.CatalogueListing;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** US-04-10: format mapping and country normalization without a database or Spring context. */
class InstitutionCatalogueServiceTest {

  private final InstitutionCatalogueRepository repository = mock(InstitutionCatalogueRepository.class);
  private final InstitutionCatalogueService service = new InstitutionCatalogueService(repository);
  private final Pageable pageable = PageRequest.of(0, 50, Sort.by("name", "id"));

  @Test
  void repeatedAndCommaSeparatedCountriesAreNormalizedAndDeduplicated() {
    when(repository.searchCatalogue(eq(null), eq("CH,DE"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    assertThat(service.searchCatalogue(null, List.of("de, CH", "ch", "  "), pageable).getContent())
        .isEmpty();

    verify(repository).searchCatalogue(eq(null), eq("CH,DE"), any(Pageable.class));
  }

  @Test
  void blankCountriesAreTheSameAsNoCountryFilter() {
    when(repository.searchCatalogue(eq("Bank"), eq(null), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.searchCatalogue("Bank", List.of("", " , "), pageable);

    verify(repository).searchCatalogue(eq("Bank"), eq(null), any(Pageable.class));
  }

  @Test
  void malformedCountryIsRejectedBeforeTheDatabaseIsRead() {
    assertThatThrownBy(() -> service.searchCatalogue(null, List.of("chx"), pageable))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ProblemCode.VALIDATION_FAILED));
  }

  @Test
  void aggregatedFormatsAreReturnedInTheResponseAsAList() {
    CatalogueListing entry = mock(CatalogueListing.class);
    UUID id = UUID.randomUUID();
    when(entry.getId()).thenReturn(id);
    when(entry.getName()).thenReturn("PostFinance");
    when(entry.getCountry()).thenReturn("CH");
    when(entry.getInstitutionType()).thenReturn("BANK");
    when(entry.getImportFormats()).thenReturn("CAMT,CSV,PDF,SPREADSHEET");
    when(repository.searchCatalogue(eq(null), eq(null), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entry)));

    var actual = service.searchCatalogue(null, null, pageable).getContent().get(0);

    assertThat(actual.id()).isEqualTo(id);
    assertThat(actual.importFormats()).containsExactly("CAMT", "CSV", "PDF", "SPREADSHEET");
  }

  @Test
  void noFormatsMeansAnEmptyListRatherThanNull() {
    CatalogueListing entry = mock(CatalogueListing.class);
    when(entry.getImportFormats()).thenReturn("");
    when(repository.searchCatalogue(eq(null), eq(null), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entry)));

    assertThat(service.searchCatalogue(null, null, pageable).getContent().get(0).importFormats())
        .isEmpty();
  }
}
