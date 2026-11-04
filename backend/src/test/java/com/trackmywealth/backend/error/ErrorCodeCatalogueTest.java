package com.trackmywealth.backend.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * US-29-04 (#374): {@code docs/api/error-codes.md} is the registry, rendered - every code with its
 * status, meaning, EN and DE text and client reaction. A code added without regenerating the file
 * fails here and names it; {@code -Derror-codes.write=true} rewrites it.
 */
class ErrorCodeCatalogueTest {

  // Maven runs the tests from backend/.
  private static final Path CATALOGUE = Path.of("..", "docs", "api", "error-codes.md");

  @Test
  void theCommittedCatalogueIsTheRenderedRegistry() throws IOException {
    String rendered = ErrorCodeCatalogue.render(Messages.EN, Messages.DE);
    if (Boolean.getBoolean("error-codes.write")) {
      Files.createDirectories(CATALOGUE.getParent());
      Files.writeString(CATALOGUE, rendered, StandardCharsets.UTF_8);
    }
    assertThat(CATALOGUE).as("%s exists", CATALOGUE).exists();
    assertThat(Files.readString(CATALOGUE, StandardCharsets.UTF_8))
        .as(
            "%s is out of date - regenerate it: ./mvnw test -Dtest=ErrorCodeCatalogueTest"
                + " -Derror-codes.write=true",
            CATALOGUE)
        .isEqualTo(rendered);
  }

  @Test
  void everyCodeHasItsTextsAndAReactionInTheCatalogue() {
    String rendered = ErrorCodeCatalogue.render(Messages.EN, Messages.DE);

    assertThat(rendered).doesNotContain("**missing**");
    for (ProblemCode code : ProblemCode.values()) {
      assertThat(rendered)
          .contains(
              "| `" + code.name() + "` | " + code.status().value(),
              "`" + code.reaction().name() + "` | `" + code.messageKey() + "`",
              Messages.DE.getProperty(code.messageKey()));
    }
    for (ImportRowCode code : ImportRowCode.values()) {
      assertThat(rendered)
          .contains("| `" + code.name() + "` | `" + code.kind().name() + "`")
          .contains(Messages.DE.getProperty(code.messageKey()));
    }
  }
}
