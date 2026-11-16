package com.castly.engagement;

import java.time.*;

public final class QuietHours {
  private QuietHours() {}

  public static Instant nextAllowed(Instant now, String zone, int start, int end) {
    if (start == end) return now;
    var local = now.atZone(ZoneId.of(zone));
    int hour = local.getHour();
    boolean quiet = start < end ? hour >= start && hour < end : hour >= start || hour < end;
    if (!quiet) return now;
    var date = local.toLocalDate();
    if (start > end && hour >= start) date = date.plusDays(1);
    return date.atTime(end, 0).atZone(local.getZone()).toInstant();
  }

  public static Instant retry(Instant now, int attempt) {
    return now.plusSeconds(Math.min(21600, 30L * (1L << Math.min(attempt, 9))));
  }
}
