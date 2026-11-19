package com.castly.discovery;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedProviderTest {
  private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final SuggestionProvider.Signals SIGNALS =
      new SuggestionProvider.Signals(new int[] {1, 0}, new int[] {10, 20}, new boolean[] {true, false});

  @Test
  void timeoutThrowAndMalformedOutputFallBackWithoutAddingCandidates() {
    for (int mode = 0; mode < 4; mode++) {
      final int behavior = mode;
      var provider = new SuggestionProvider() {
        public boolean available() { return true; }
        public Optional<List<Integer>> reorder(Signals signals) {
          if (behavior == 0) {
            try { Thread.sleep(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
          }
          if (behavior == 1) throw new IllegalStateException("provider fixture");
          if (behavior == 3) return null;
          return Optional.of(List.of(1, 1));
        }
      };
      long start = System.nanoTime();
      var bounded = new BoundedProvider(provider);
      assertTrue(bounded.reorder(ACTOR,List.of("a","b"),SIGNALS,"confirmed").isEmpty());
      bounded.shutdown();
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 700);
    }
  }

  @Test
  void shortCacheBindsActorCandidatesAndConsentAndForgetInvalidatesInflight() throws Exception {
    var calls = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var provider = new SuggestionProvider() {
      public boolean available() { return true; }
      public Optional<List<Integer>> reorder(Signals signals) {
        calls.incrementAndGet(); entered.countDown();
        try { release.await(1,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return Optional.of(List.of(1,0));
      }
    };
    var bounded = new BoundedProvider(provider);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var running = executor.submit(() -> bounded.reorder(ACTOR,List.of("a","b"),SIGNALS,"consent-v1"));
      assertTrue(entered.await(1,TimeUnit.SECONDS));
      bounded.forget(ACTOR);
      release.countDown();
      assertTrue(running.get(1,TimeUnit.SECONDS).isEmpty());
      assertEquals(List.of(1,0), bounded.reorder(ACTOR,List.of("a","b"),SIGNALS,"consent-v1").orElseThrow());
      assertEquals(2,calls.get());
      bounded.reorder(ACTOR,List.of("a","b"),SIGNALS,"consent-v1");
      assertEquals(2,calls.get());
      bounded.reorder(UUID.randomUUID(),List.of("a","b"),SIGNALS,"consent-v1");
      bounded.reorder(ACTOR,List.of("a","b"),SIGNALS,"consent-v2");
      bounded.reorder(ACTOR,List.of("b","a"),SIGNALS,"consent-v1");
      assertEquals(5,calls.get());
    }
    bounded.shutdown();
  }
}
