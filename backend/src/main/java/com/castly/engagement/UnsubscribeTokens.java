package com.castly.engagement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

public final class UnsubscribeTokens {
  private final String key;

  public UnsubscribeTokens(String key) {
    this.key = key;
  }

  public String issue(UUID delivery, Instant now) {
    if (key.length() < 32) throw new IllegalStateException("Unsubscribe signing unavailable");
    String payload = delivery + "." + now.plus(Duration.ofDays(30)).getEpochSecond();
    return payload + "." + Phase6Settings.hmac(key, payload);
  }

  public Optional<UUID> verify(String token, Instant now) {
    if (key.length() < 32 || token == null || token.length() > 150) return Optional.empty();
    try {
      String[] parts = token.split("\\.");
      if (parts.length != 3 || !parts[2].matches("[a-f0-9]{64}")) return Optional.empty();
      long expiry = Long.parseLong(parts[1]);
      if (expiry < now.getEpochSecond() || expiry > now.plus(Duration.ofDays(31)).getEpochSecond())
        return Optional.empty();
      String wanted = Phase6Settings.hmac(key, parts[0] + "." + parts[1]);
      if (!MessageDigest.isEqual(
          wanted.getBytes(StandardCharsets.US_ASCII), parts[2].getBytes(StandardCharsets.US_ASCII)))
        return Optional.empty();
      return Optional.of(UUID.fromString(parts[0]));
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
