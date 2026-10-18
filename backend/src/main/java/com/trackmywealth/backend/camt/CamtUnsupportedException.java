package com.trackmywealth.backend.camt;

/**
 * Well-formed XML, but not a message this reader supports: another namespace, another message (such
 * as {@code camt.054}) or another version than {@link CamtXmlReader#SUPPORTED_VERSIONS}.
 */
public class CamtUnsupportedException extends CamtFormatException {

  private static final long serialVersionUID = 1L;

  public CamtUnsupportedException(String message) {
    super(message);
  }
}
