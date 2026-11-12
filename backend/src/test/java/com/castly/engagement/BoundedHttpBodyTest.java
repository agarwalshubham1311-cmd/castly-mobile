package com.castly.engagement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BoundedHttpBodyTest {
  @Test
  void oversizedStreamingBodyCancelsBeforeBuffering() {
    var subscriber = new BoundedHttpBody.Subscriber();
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    subscriber.onSubscribe(
        new Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {
            cancelled.set(true);
          }
        });
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[65537])));
    assertTrue(cancelled.get());
    assertThrows(
        CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
  }

  @Test
  void fullResponseDeadlineCancelsStalledProvider() throws Exception {
    var client = mock(HttpClient.class);
    var pending = new CompletableFuture<HttpResponse<byte[]>>();
    when(client.sendAsync(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
        .thenReturn(pending);
    assertThrows(
        TimeoutException.class,
        () ->
            BoundedHttpBody.send(
                client,
                HttpRequest.newBuilder(URI.create("https://fixture.invalid")).build(),
                Duration.ofMillis(50)));
    assertTrue(pending.isCancelled());
  }
}
