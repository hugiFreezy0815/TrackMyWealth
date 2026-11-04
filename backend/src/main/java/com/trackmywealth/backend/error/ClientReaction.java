package com.trackmywealth.backend.error;

/**
 * What a client typically does on a {@link ProblemCode} (US-29-04, #374): the column "Client
 * reaction" of {@code docs/api/error-codes.md}. A recommendation for the app, not a server rule.
 */
public enum ClientReaction {
  FIX_INPUT("Mark the rejected input, let the member correct it, then send the request again."),
  SHOW("Show the message; the same request fails again until the member changes something."),
  RELOAD("Reload the current state: it changed, and the request no longer applies to it."),
  RELOAD_AND_RETRY("Reload the resource, then retry with its new ETag in If-Match."),
  RETRY("Send the same request again right away."),
  RETRY_LATER("Retry the same request later, after Retry-After seconds when that header is sent."),
  REAUTHENTICATE("Refresh the access token or sign in again, then retry."),
  REPORT("Show a generic error and offer to report it, quoting the correlationId.");

  private final String sentence;

  ClientReaction(String description) {
    this.sentence = description;
  }

  /** One sentence for the catalogue. */
  public String description() {
    return sentence;
  }
}
