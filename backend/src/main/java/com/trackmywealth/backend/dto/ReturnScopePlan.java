package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * #607: one scope of a period return - the day its return starts ({@code null} when nothing in it
 * is tracked during the period), the values it reads, and its tracked accounts.
 */
public record ReturnScopePlan(
    @Nullable LocalDate start, List<ReturnTerm> terms, Set<UUID> members) {

  public static final ReturnScopePlan EMPTY = new ReturnScopePlan(null, List.of(), Set.of());

  public ReturnScopePlan {
    terms = List.copyOf(terms);
    members = Set.copyOf(members);
  }
}
