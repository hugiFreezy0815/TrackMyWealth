package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * US-29-02 (#372): the drift check's finding, without starting the application - a PR that changes
 * a response field but not {@code api/openapi.yaml} fails, and the failure names the file.
 */
class OpenApiDriftTest {

  private static final String COMMITTED =
      """
      paths:
        /api/v1/accounts:
          get:
            operationId: listAccounts
      components:
        schemas:
          AccountListItemResponse:
            properties:
              name:
                type: string
      """;

  @Test
  void theSameDocumentHasNoDrift() {
    assertThat(OpenApiContractTest.driftOf(COMMITTED, COMMITTED)).isEmpty();
  }

  @Test
  void aCheckoutWithWindowsLineEndingsHasNoDrift() {
    assertThat(OpenApiContractTest.driftOf(COMMITTED.replace("\n", "\r\n"), COMMITTED)).isEmpty();
  }

  @Test
  void aChangedResponseFieldIsDriftNamingTheFileAndTheLine() {
    String served = COMMITTED.replace(" name:", " displayName:");

    assertThat(OpenApiContractTest.driftOf(COMMITTED, served))
        .hasValueSatisfying(
            finding ->
                assertThat(finding)
                    .startsWith("api/openapi.yaml differs from the API the code serves")
                    .contains("first difference at line 9")
                    .containsPattern("committed ' +name:', served ' +displayName:'")
                    .contains("-Dopenapi.update=true"));
  }

  @Test
  void anOperationOnlyInTheCodeIsDriftAtTheEndOfTheCommittedFile() {
    String served = COMMITTED + "x-added: true\n";

    assertThat(OpenApiContractTest.driftOf(COMMITTED, served))
        .hasValueSatisfying(finding -> assertThat(finding).contains("committed ''"));
    assertThat(OpenApiContractTest.driftOf(COMMITTED.strip(), COMMITTED))
        .hasValueSatisfying(finding -> assertThat(finding).contains("<end of file>"));
  }
}
