package com.trackmywealth.backend.camt;

import java.io.IOException;

/**
 * A file is no readable CAMT statement: malformed XML or ZIP, a document type declaration or entity
 * reference, a ZIP entry that is no XML, or no statement at all. Its message never repeats the
 * file's content; a parser exception it wraps may, so it is never shown or logged.
 */
public class CamtFormatException extends IOException {

  private static final long serialVersionUID = 1L;

  public CamtFormatException(String message) {
    super(message);
  }

  public CamtFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
