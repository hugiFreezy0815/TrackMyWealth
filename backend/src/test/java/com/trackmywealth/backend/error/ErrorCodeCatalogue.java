package com.trackmywealth.backend.error;

import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Renders {@code docs/api/error-codes.md} from the registries (US-29-04, #374): {@link
 * ProblemCode}, {@link ClientReaction}, {@link ImportRowCode} and their EN/DE texts.
 */
final class ErrorCodeCatalogue {

  private ErrorCodeCatalogue() {}

  static String render(Properties english, Properties german) {
    StringBuilder md = new StringBuilder();
    md.append("# API error codes\n\n")
        .append(
            "<!-- Generated from error.ProblemCode, error.ClientReaction and error.ImportRowCode")
        .append(" by ErrorCodeCatalogueTest. Do not edit by hand; regenerate with\n")
        .append("     cd backend && ./mvnw test -Dtest=ErrorCodeCatalogueTest")
        .append(" -Derror-codes.write=true -->\n\n")
        .append("Every error response is an RFC 9457 `application/problem+json` body with a stable")
        .append(
            " `code` (US-29-04, #374). A client branches on `code`, never on `detail`: `detail`")
        .append(" is the server's English explanation for developers and logs. The app shows the")
        .append(" text of the code's message key in the member's language; the keys and texts")
        .append(" below are the server's own (`backend/src/main/resources/messages*.properties`).")
        .append("\n\n")
        .append("Codes are part of the API contract: a code is never renamed or removed. The")
        .append(" OpenAPI contract lists every code in `ApiProblem.code`, and per operation the")
        .append(" codes it can return (`x-problem-codes`, and one error response per status).\n\n");

    md.append("## Client reactions\n\n| Reaction | What the client does |\n|---|---|\n");
    for (ClientReaction reaction : ClientReaction.values()) {
      md.append("| `").append(reaction.name()).append("` | ").append(reaction.description());
      md.append(" |\n");
    }

    md.append("\n## Problem codes\n\n")
        .append("| Code | Status | Meaning | Client reaction | Message key | EN | DE |\n")
        .append("|---|---|---|---|---|---|---|\n");
    for (ProblemCode code : ProblemCode.values()) {
      md.append("| `")
          .append(code.name())
          .append("` | ")
          .append(
              Stream.concat(
                      Stream.of(code.status()),
                      code.statuses().stream().filter(status -> status != code.status()))
                  .map(status -> Integer.toString(status.value()))
                  .collect(Collectors.joining(", ")))
          .append(" | ")
          .append(cell(code.meaning()))
          .append(" | `")
          .append(code.reaction().name())
          .append("` | `")
          .append(code.messageKey())
          .append("` | ")
          .append(cell(english.getProperty(code.messageKey())))
          .append(" | ")
          .append(cell(german.getProperty(code.messageKey())))
          .append(" |\n");
    }

    md.append("\n## Import row codes\n\n")
        .append("Not error responses: an import goes on, and each row or holdings line says in")
        .append(" `error` or `warnings` (`ImportRowErrorResponse`: `code`, `args`, `message`) why")
        .append(" it is not imported or what to check. `message` is already in the caller's")
        .append(" language; the texts take the row's `args` in order as `{0}`, `{1}`, ...\n\n")
        .append("| Kind | Meaning |\n|---|---|\n");
    for (ImportRowCode.Kind kind : ImportRowCode.Kind.values()) {
      md.append("| `").append(kind.name()).append("` | ").append(kind.description()).append(" |\n");
    }
    md.append("\n| Code | Kind | Meaning | Message key | EN | DE |\n")
        .append("|---|---|---|---|---|---|\n");
    for (ImportRowCode code : ImportRowCode.values()) {
      md.append("| `")
          .append(code.name())
          .append("` | `")
          .append(code.kind().name())
          .append("` | ")
          .append(cell(code.meaning()))
          .append(" | `")
          .append(code.messageKey())
          .append("` | ")
          .append(cell(english.getProperty(code.messageKey())))
          .append(" | ")
          .append(cell(german.getProperty(code.messageKey())))
          .append(" |\n");
    }
    return md.toString();
  }

  // A table cell: a missing text shows as such, and a pipe would end the cell.
  private static String cell(String text) {
    return text == null ? "**missing**" : text.replace("|", "\\|");
  }
}
