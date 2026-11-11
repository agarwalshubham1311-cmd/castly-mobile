package com.castly.discovery;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public final class DisabledSuggestionProvider implements SuggestionProvider {
  @Override public boolean available() { return false; }
  @Override public Optional<List<Integer>> reorder(Signals signals) { return Optional.empty(); }
}
