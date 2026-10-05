package com.trackmywealth.backend.pdf;

import java.io.IOException;

/**
 * A PDF holds more than one statement may: a stream that decodes to too many bytes, an image of too
 * many pixels, too many drawing operations, or a read that takes too long. Its message names the
 * limit, never the file's content.
 */
public class PdfLimitException extends IOException {

  private static final long serialVersionUID = 1L;

  public PdfLimitException(String message) {
    super(message);
  }
}
