package com.castly.trust;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TrustRefreshQueueTest {
  @Test
  void invalidationDuringFirstRefreshRunsAgainWithoutLosingTheEvent() throws Exception {
    var firstStarted = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondFinished = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var failures = new AtomicInteger();
    UUID subject = UUID.randomUUID();
    try (var queue =
        new TrustRefreshQueue(
            id -> {
              int call = calls.incrementAndGet();
              if (call == 1) {
                firstStarted.countDown();
                try {
                  assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
              } else secondFinished.countDown();
            },
            failures::incrementAndGet,
            failures::incrementAndGet)) {
      queue.submit(subject);
      assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
      queue.submit(subject);
      queue.submit(subject);
      releaseFirst.countDown();
      assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
      assertEquals(0, failures.get());
    } finally {
      releaseFirst.countDown();
    }
  }
}
