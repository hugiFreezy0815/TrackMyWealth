package com.trackmywealth.backend.client;

/** An FX rate provider could not be reached or answered with something unreadable (#223). */
public class FxRateProviderException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public FxRateProviderException(String message) {
    super(message);
  }

  public FxRateProviderException(String message, Throwable cause) {
    super(message, cause);
  }
}
