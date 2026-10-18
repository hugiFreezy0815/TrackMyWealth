package com.trackmywealth.backend.camt;

/**
 * A date choice ({@code BookgDt}, {@code ValDt}, {@code Bal/Dt}) as written: its {@code Dt} or its
 * {@code DtTm}, at most one of them set. Not yet validated.
 */
public record CamtDate(String date, String dateTime) {

  public boolean isEmpty() {
    return date == null && dateTime == null;
  }
}
