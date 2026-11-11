package com.castly.discovery;

import java.time.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
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
  private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, AtomicLong> generations = new ConcurrentHashMap<>();
  private record Entry(List<Integer> order, Instant expires) {}
  public BoundedProvider(SuggestionProvider provider) { this.provider = provider; }

  public Optional<List<Integer>> reorder(UUID actor, List<String> currentIds,
      SuggestionProvider.Signals signals, String confirmedContext) {
    if (!provider.available() || currentIds.isEmpty()) return Optional.empty();
    long generation = generations.computeIfAbsent(actor, ignored -> new AtomicLong()).get();
    String key = actor + ":" + generation + ":" + digest(confirmedContext) + ":" + currentIds + ":" + Arrays.toString(signals.skillMatches())
        + ":" + Arrays.toString(signals.budgetFit()) + ":" + Arrays.toString(signals.confirmedAvailability());
    Entry saved = cache.get(key);
    if (saved != null && saved.expires().isAfter(Instant.now())) return Optional.of(saved.order());
    Future<Optional<List<Integer>>> request;
    try { request = workers.submit(() -> provider.reorder(signals)); }
    catch (RejectedExecutionException busy) { return Optional.empty(); }
    try {
      var result = request.get(250, TimeUnit.MILLISECONDS);
      if (result.isEmpty() || !valid(result.get(), currentIds.size())) return Optional.empty();
      if (generations.get(actor).get() != generation) return Optional.empty();
      if (cache.size() >= 256) cache.clear();
      var order = List.copyOf(result.get());
      cache.put(key, new Entry(order, Instant.now().plusSeconds(15)));
      return Optional.of(order);
    } catch (InterruptedException interrupted) {
      request.cancel(true); Thread.currentThread().interrupt(); return Optional.empty();
    } catch (TimeoutException | ExecutionException failed) {
      request.cancel(true); return Optional.empty();
    }
  }

  public void forget(UUID actor) {
    generations.computeIfAbsent(actor, ignored -> new AtomicLong()).incrementAndGet();
    cache.keySet().removeIf(key -> key.startsWith(actor + ":"));
  }
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
