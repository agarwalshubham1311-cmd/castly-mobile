package com.castly.discovery;

import java.util.*;

/** Optional future provider contract. No names, identifiers, prose, URLs or private data cross it. */
public interface SuggestionProvider {
  record Signals(int[] skillMatches, int[] budgetFit, boolean[] confirmedAvailability) {}
  boolean available();
  Optional<List<Integer>> reorder(Signals signals);
}
