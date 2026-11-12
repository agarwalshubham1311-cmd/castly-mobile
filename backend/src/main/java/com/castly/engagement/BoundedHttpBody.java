package com.castly.engagement;

import java.io.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounds the complete response body and deadline, including a stalled stream after headers. */
public final class BoundedHttpBody {
  private BoundedHttpBody() {}

  public static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, Duration deadline)
      throws Exception {
    var pending = client.sendAsync(request, info -> new Subscriber());
    try {
      return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      pending.cancel(true);
      throw e;
    }
  }

  static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    public CompletionStage<byte[]> getBody() {
      return body;
    }

    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      s.request(1);
    }

    public void onNext(List<ByteBuffer> chunks) {
      if (body.isDone()) {
        subscription.cancel();
        return;
      }
      for (var chunk : chunks) {
        int n = chunk.remaining();
        if (n > 65536 - bytes.size()) {
          subscription.cancel();
          body.completeExceptionally(new IOException("Provider response exceeds limit"));
          return;
        }
        byte[] copy = new byte[n];
        chunk.get(copy);
        bytes.writeBytes(copy);
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      body.completeExceptionally(error);
    }

    public void onComplete() {
      body.complete(bytes.toByteArray());
    }
  }
}
