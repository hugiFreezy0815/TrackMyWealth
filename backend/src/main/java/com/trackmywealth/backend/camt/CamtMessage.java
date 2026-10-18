package com.trackmywealth.backend.camt;

import java.util.List;

/**
 * One CAMT XML document.
 *
 * @param messageType {@link CamtXmlReader#STATEMENT} ({@code camt.053}, end of day) or {@link
 *     CamtXmlReader#REPORT} ({@code camt.052}, intraday)
 * @param version the message version, such as {@code 001.08}
 */
public record CamtMessage(String messageType, String version, List<CamtStatement> statements) {

  public CamtMessage {
    statements = List.copyOf(statements);
  }

  /** An end-of-day statement, whose closing balance is a statement balance. */
  public boolean isStatement() {
    return CamtXmlReader.STATEMENT.equals(messageType);
  }
}
