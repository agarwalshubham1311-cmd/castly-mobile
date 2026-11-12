package com.castly.discovery;

import java.time.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/** Only short-lived permutations of current public candidates are cached; every caller re-queries source facts. */
@Component
public class BoundedProvider {
  private final SuggestionProvider provider;
  private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(8), task -> {
        Thread thread = new Thread(task, "phase5-provider");
        thread.setDaemon(true);
        return thread;
      }, new ThreadPoolExecutor.AbortPolicy());
  private final Map<String, Entry> cache = new HashMap<>();
  private final Map<UUID, State> states = new HashMap<>();
  private final Object guard = new Object();
  private static final class State { long generation; int active; }
  private record Entry(List<Integer> order, Instant expires) {}
  public BoundedProvider(SuggestionProvider provider) { this.provider = provider; }

  public Optional<List<Integer>> reorder(UUID actor, List<String> currentIds,
      SuggestionProvider.Signals signals, String confirmedContext) {
    if (!provider.available() || currentIds.isEmpty()) return Optional.empty();
    State state;
    long generation;
    synchronized (guard) {
      if (states.size() >= 256 && !states.containsKey(actor)) {
        var idle = states.entrySet().stream().filter(e -> e.getValue().active == 0)
            .map(Map.Entry::getKey).findFirst();
        if (idle.isEmpty()) return Optional.empty();
        UUID removed = idle.get();
        states.remove(removed);
        cache.keySet().removeIf(key -> key.startsWith(removed + ":"));
      }
      state = states.computeIfAbsent(actor, ignored -> new State());
      state.active++;
      generation = state.generation;
    }
    try {
    String key = actor + ":" + generation + ":" + digest(confirmedContext) + ":" + currentIds + ":" + Arrays.toString(signals.skillMatches())
        + ":" + Arrays.toString(signals.budgetFit()) + ":" + Arrays.toString(signals.confirmedAvailability());
    synchronized (guard) {
      Entry saved = cache.get(key);
      if (state.generation != generation) return Optional.empty();
      if (saved != null && saved.expires().isAfter(Instant.now())) return Optional.of(saved.order());
    }
    Future<Optional<List<Integer>>> request;
    try { request = workers.submit(() -> provider.reorder(signals)); }
    catch (RejectedExecutionException busy) { return Optional.empty(); }
    try {
      var result = request.get(250, TimeUnit.MILLISECONDS);
      if (result == null || result.isEmpty() || !valid(result.get(), currentIds.size())) return Optional.empty();
      var order = List.copyOf(result.get());
      synchronized (guard) {
        if (state.generation != generation) return Optional.empty();
        if (cache.size() >= 256) cache.clear();
        cache.put(key, new Entry(order, Instant.now().plusSeconds(15)));
      }
      return Optional.of(order);
    } catch (InterruptedException interrupted) {
      request.cancel(true); Thread.currentThread().interrupt(); return Optional.empty();
    } catch (TimeoutException | ExecutionException failed) {
      request.cancel(true); return Optional.empty();
    }
    } finally {
      synchronized (guard) { state.active--; }
    }
  }

  public void forget(UUID actor) {
    synchronized (guard) {
      State state = states.get(actor);
      if (state != null) state.generation++;
      cache.keySet().removeIf(key -> key.startsWith(actor + ":"));
    }
  }
  @PreDestroy public void shutdown() { workers.shutdownNow(); }
  private String digest(String source) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) { throw new IllegalStateException(impossible); }
  }
  private boolean valid(List<Integer> order, int size) {
    return order.size() == size && order.stream().allMatch(Objects::nonNull)
        && new HashSet<>(order).equals(java.util.stream.IntStream.range(0,size).boxed()
            .collect(java.util.stream.Collectors.toSet()));
  }
}
