package com.castly.engagement;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class DeliveryRulesTest {
  @Test
  void quietHoursRespectOvernightDaytimeAndDst() {
    var now = Instant.parse("2026-03-08T06:30:00Z");
    assertEquals(
        Instant.parse("2026-03-08T12:00:00Z"),
        QuietHours.nextAllowed(now, "America/New_York", 22, 8));
    assertEquals(now, QuietHours.nextAllowed(now, "UTC", 0, 0));
    assertEquals(Instant.parse("2026-03-08T08:00:00Z"), QuietHours.nextAllowed(now, "UTC", 5, 8));
    assertEquals(
        Instant.parse("2026-11-13T08:00:00Z"),
        QuietHours.nextAllowed(Instant.parse("2026-11-12T23:00:00Z"), "UTC", 22, 8));
  }

  @Test
  void retryBackoffIsBounded() {
    assertEquals(Instant.EPOCH.plusSeconds(60), QuietHours.retry(Instant.EPOCH, 1));
    assertEquals(Instant.EPOCH.plusSeconds(15360), QuietHours.retry(Instant.EPOCH, 20));
  }

  @Test
  void unsubscribeIsScopedTamperResistantAndExpires() {
    var now = Instant.parse("2026-11-12T00:00:00Z");
    var tokens = new UnsubscribeTokens("fixture-unsubscribe-key-at-least-32-characters");
    var id = UUID.randomUUID();
    String token = tokens.issue(id, now);
    assertEquals(id, tokens.verify(token, now).orElseThrow());
    assertTrue(tokens.verify(token + "bad", now).isEmpty());
    assertTrue(tokens.verify(token, now.plus(Duration.ofDays(31))).isEmpty());
    assertTrue(new UnsubscribeTokens("").verify(token, now).isEmpty());
  }

  @Test
  void templatesEscapeLinksAndDistinguishOptional() {
    String html =
        NotificationTemplates.html("message_received", "https://castly.invalid/?x=\"<", null);
    assertTrue(html.contains("Castly"));
    assertFalse(html.contains("?x=\"<"));
    assertTrue(html.contains("&quot;&lt;"));
    assertTrue(NotificationTemplates.optional("saved_digest"));
    assertFalse(NotificationTemplates.optional("booking_accepted"));
  }

  @Test
  void disabledProvidersCannotSend() {
    var flags = new Phase6Settings();
    var provider =
        new HttpDeliveryProvider(flags, new com.fasterxml.jackson.databind.ObjectMapper());
    assertEquals(
        DeliveryProvider.Status.DISABLED,
        provider
            .send(
                new DeliveryProvider.Envelope(
                    UUID.randomUUID(),
                    "push",
                    "ExpoPushToken[fixture_token_12345]",
                    "booking_requested",
                    "",
                    null))
            .status());
    assertEquals(DeliveryProvider.Status.DISABLED, provider.receipt("receipt").status());
    assertFalse(flags.configuration().values().stream().anyMatch(Boolean.TRUE::equals));
  }

  @Test
  void webhookSignaturesRequireFreshAuthenticBoundedBody() throws Exception {
    var now = Instant.parse("2026-11-12T00:00:00Z");
    byte[] key = "fixture-webhook-secret-at-least-32".getBytes(StandardCharsets.UTF_8),
        body = "{}".getBytes(StandardCharsets.UTF_8);
    String id = "evt_fixture", timestamp = "" + now.getEpochSecond();
    var m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(key, "HmacSHA256"));
    String signature =
        "v1,"
            + Base64.getEncoder()
                .encodeToString(
                    m.doFinal((id + "." + timestamp + ".{}").getBytes(StandardCharsets.UTF_8)));
    String secret = "whsec_" + Base64.getEncoder().encodeToString(key);
    assertTrue(EmailWebhook.verify(secret, id, timestamp, signature, body, now));
    assertFalse(
        EmailWebhook.verify(
            secret, id, timestamp, signature, "changed".getBytes(StandardCharsets.UTF_8), now));
    assertFalse(EmailWebhook.verify(secret, id, timestamp, signature, body, now.plusSeconds(301)));
    assertFalse(EmailWebhook.verify(secret, id, timestamp, signature, new byte[65537], now));
  }

  @Test
  void analyticsKeysAreStablePseudonymousAndActorIsolated() {
    var f = new Phase6Settings();
    f.analyticsKey = "fixture-analytics-key-at-least-32-characters";
    var a = UUID.randomUUID();
    assertEquals(f.subject(a), f.subject(a));
    assertNotEquals(f.subject(a), f.subject(UUID.randomUUID()));
    assertFalse(f.subject(a).contains(a.toString()));
    assertEquals(64, f.subject(a).length());
  }
}
