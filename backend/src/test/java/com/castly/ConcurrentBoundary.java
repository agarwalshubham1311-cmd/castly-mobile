package com.castly;

import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.concurrent.*;

/** Test-only event ordering, independent of provider deadlines and Docker startup speed. */
final class ConcurrentBoundary implements AutoCloseable {
  private static final Duration LIMIT = Duration.ofSeconds(30);
  private final String name;
  private final CompletableFuture<Void> reached = new CompletableFuture<>();
  private final CompletableFuture<Void> released = new CompletableFuture<>();

  ConcurrentBoundary(String name) {
    this.name = name;
  }

  void hold() throws Exception {
    reached.complete(null);
    // A deadline is a test failure, never permission to resume before the assertions finish.
    released.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  void awaitReached(Future<?> request) throws Exception {
    long deadline = System.nanoTime() + LIMIT.toNanos();
    while (!reached.isDone()) {
      if (request.isDone()) {
        request.get(); // Propagate the original worker/HTTP/SQL failure instead of a latch timeout.
        fail(name + " request completed before reaching the expected boundary");
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) fail("Timed out reaching " + name);
      try {
        reached.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
      } catch (TimeoutException pollAgain) {
        /* Check the worker before waiting again. */
      }
    }
  }

  void release() {
    released.complete(null);
  }

  @Override
  public void close() {
    release();
  }
}
