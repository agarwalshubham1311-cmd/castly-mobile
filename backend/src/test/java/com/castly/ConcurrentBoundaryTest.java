package com.castly;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class ConcurrentBoundaryTest {
  @Test
  void failureBeforeGatewayEntrySurfacesOriginalCauseInsteadOfLatchTimeout() {
    var failure = new IllegalStateException("sanitized fixture SQL failure");
    try (var boundary = new ConcurrentBoundary("upload")) {
      var thrown =
          assertThrows(
              ExecutionException.class,
              () -> boundary.awaitReached(CompletableFuture.failedFuture(failure)));
      assertSame(failure, thrown.getCause());
    }
  }

  @Test
  void earlyHttpCompletionCannotMasqueradeAsReachingProviderBoundary() {
    try (var boundary = new ConcurrentBoundary("provider")) {
      var thrown =
          assertThrows(
              AssertionError.class,
              () -> boundary.awaitReached(CompletableFuture.completedFuture(null)));
      assertTrue(thrown.getMessage().contains("completed before reaching"));
    }
  }

  @Test
  void resourceCleanupReleasesHeldWorkerBeforeExecutorShutdown() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var boundary = new ConcurrentBoundary("upload")) {
      var pending =
          executor.submit(
              () -> {
                boundary.hold();
                return true;
              });
      boundary.awaitReached(pending);
      assertFalse(pending.isDone(), "The worker must stay paused until explicitly released");
      boundary.close();
      assertTrue(pending.get(5, TimeUnit.SECONDS));
    }
  }
}
