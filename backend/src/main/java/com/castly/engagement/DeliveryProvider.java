package com.castly.engagement;

import java.util.UUID;

public interface DeliveryProvider {
  enum Status {
    DISABLED,
    ACCEPTED,
    DELIVERED,
    RETRY,
    INVALID,
    PERMANENT,
    UNCERTAIN,
    PENDING
  }

  record Result(Status status, String receipt) {
    public static Result of(Status status) {
      return new Result(status, null);
    }
  }

  record Envelope(
      UUID id, String channel, String destination, String kind, String link, String unsubscribe) {}

  Result send(Envelope envelope);

  Result receipt(String receipt);
}
