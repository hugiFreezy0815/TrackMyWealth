package com.trackmywealth.backend.camt;

import java.io.IOException;

/**
 * A CAMT file holds more than one import may: too many bytes, elements, entries or archive entries,
 * nesting that is too deep, an overlong text, or a ZIP entry that inflates too far. Its message
 * names the limit, never the file's content.
 */
public class CamtLimitException extends IOException {

  private static final long serialVersionUID = 1L;

  public CamtLimitException(String message) {
    super(message);
  }
}
