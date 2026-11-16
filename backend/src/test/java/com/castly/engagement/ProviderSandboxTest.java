package com.castly.engagement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderSandboxTest {
  @SuppressWarnings("unchecked")
  private HttpResponse<byte[]> response(String body, int status) {
    var r = (HttpResponse<byte[]>) mock(HttpResponse.class);
    when(r.statusCode()).thenReturn(status);
    when(r.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
    return r;
  }

  private Phase6Settings flags() {
    var f = new Phase6Settings();
    f.worker = true;
    f.push = true;
    f.email = true;
    f.pushProvider = "expo";
    f.emailProvider = "resend";
    f.emailFrom = "castly@fixture.invalid";
    f.emailKey = "local-sandbox-fixture-not-a-provider-key";
    return f;
  }

  @Test
  void expoSandboxMapsAcceptanceReceiptAndKnownRejectionWithoutNetwork() throws Exception {
    var client = mock(HttpClient.class);
    var requests = new ArrayList<HttpRequest>();
    when(client.sendAsync(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
        .thenAnswer(
            inv -> {
              requests.add(inv.getArgument(0));
              return java.util.concurrent.CompletableFuture.completedFuture(
                  switch (requests.size()) {
                    case 1 ->
                        response("{\"data\":{\"status\":\"ok\",\"id\":\"ticket-fixture\"}}", 200);
                    case 2 -> response("{\"data\":{\"ticket-fixture\":{\"status\":\"ok\"}}}", 200);
                    case 3 -> response("{}", 429);
                    case 4 ->
                        response(
                            "{\"data\":{\"status\":\"error\",\"details\":{\"error\":\"DeviceNotRegistered\"}}}",
                            200);
                    default -> response("{}", 500);
                  });
            });
    var provider =
        new HttpDeliveryProvider(
            flags(), new com.fasterxml.jackson.databind.ObjectMapper(), client);
    var job =
        new DeliveryProvider.Envelope(
            UUID.randomUUID(),
            "push",
            "ExpoPushToken[local_fixture_token]",
            "message_received",
            "https://app.fixture.invalid/notifications",
            null);
    assertEquals(DeliveryProvider.Status.ACCEPTED, provider.send(job).status());
    assertEquals(DeliveryProvider.Status.DELIVERED, provider.receipt("ticket-fixture").status());
    assertEquals(DeliveryProvider.Status.RETRY, provider.send(job).status());
    assertEquals(DeliveryProvider.Status.INVALID, provider.send(job).status());
    assertEquals(DeliveryProvider.Status.UNCERTAIN, provider.send(job).status());
    assertEquals("https://exp.host/--/api/v2/push/send", requests.getFirst().uri().toString());
    assertEquals(java.time.Duration.ofSeconds(4), requests.getFirst().timeout().orElseThrow());
  }

  @Test
  void emailSandboxUsesIdempotencyAndWaitsForSignedDeliveryEvidence() throws Exception {
    var client = mock(HttpClient.class);
    var request = new java.util.concurrent.atomic.AtomicReference<HttpRequest>();
    when(client.sendAsync(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
        .thenAnswer(
            inv -> {
              request.set(inv.getArgument(0));
              return java.util.concurrent.CompletableFuture.completedFuture(
                  response("{\"id\":\"email-fixture\"}", 200));
            });
    var provider =
        new HttpDeliveryProvider(
            flags(), new com.fasterxml.jackson.databind.ObjectMapper(), client);
    UUID id = UUID.randomUUID();
    var job =
        new DeliveryProvider.Envelope(
            id,
            "email",
            "verified@fixture.invalid",
            "saved_digest",
            "https://app.fixture.invalid/engagement/open/" + id,
            "https://api.fixture.invalid/unsubscribe/opaque");
    assertEquals(DeliveryProvider.Status.ACCEPTED, provider.send(job).status());
    assertEquals(
        id.toString(), request.get().headers().firstValue("Idempotency-Key").orElseThrow());
    assertEquals("https://api.resend.com/emails", request.get().uri().toString());
    verify(client, times(1))
        .sendAsync(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
  }
}
