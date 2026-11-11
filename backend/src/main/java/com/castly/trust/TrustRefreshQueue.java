package com.castly.trust;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Bounded per-subject invalidation queue; an event during a refresh always causes another pass. */
final class TrustRefreshQueue implements AutoCloseable {
  private final ConcurrentHashMap<UUID, AtomicLong> pending = new ConcurrentHashMap<>();
  private final Consumer<UUID> refresh;
  private final Runnable failed, deferred;
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          1,
          2,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(128),
          r -> {
            var t = new Thread(r, "castly-trust-triage");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());

  TrustRefreshQueue(Consumer<UUID> refresh, Runnable failed, Runnable deferred) {
    this.refresh = refresh;
    this.failed = failed;
    this.deferred = deferred;
  }

  void submit(UUID subject) {
    var created = new AtomicReference<AtomicLong>();
    pending.compute(
        subject,
        (id, generation) -> {
          if (generation == null) {
            generation = new AtomicLong();
            created.set(generation);
          }
          generation.incrementAndGet();
          return generation;
        });
    if (created.get() != null) start(subject, created.get());
  }

  private void start(UUID subject, AtomicLong generation) {
    try {
      executor.execute(() -> drain(subject, generation));
    } catch (RejectedExecutionException full) {
      pending.remove(subject, generation);
      deferred.run();
    }
  }

  private void drain(UUID subject, AtomicLong generation) {
    for (int pass = 0; pass < 4; pass++) {
      long observed = generation.get();
      try {
        refresh.accept(subject);
      } catch (RuntimeException error) {
        failed.run();
      }
      var dirty = new AtomicBoolean();
      pending.compute(
          subject,
          (id, current) -> {
            if (current != generation) return current;
            if (current.get() != observed) {
              dirty.set(true);
              return current;
            }
            return null;
          });
      if (!dirty.get()) return;
    }
    start(subject, generation);
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException interrupted) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
