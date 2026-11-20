package com.trackmywealth.backend.dto;

import java.util.Map;

/** #607: the period return of a whole history scope and of each of its sections. */
public record PeriodReturns(PeriodReturn total, Map<PortfolioSection, PeriodReturn> sections) {

  public PeriodReturns {
    sections = Map.copyOf(sections);
  }

  public PeriodReturn section(PortfolioSection section) {
    return sections.getOrDefault(section, PeriodReturn.NONE);
  }
}
