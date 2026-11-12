package com.castly.engagement;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class VerifiedMailboxTest {
  @Test
  void trustedMailboxRequiresProviderConfirmationAndMatchingSubject() throws Exception {
    UUID user = UUID.randomUUID();
    var body = new java.util.concurrent.atomic.AtomicReference<String>();
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/auth/v1/user",
        exchange -> {
          byte[] bytes = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var contacts =
          new com.castly.trust.TrustedContactService(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/v1",
              "sb_publishable_fixture",
              new com.fasterxml.jackson.databind.ObjectMapper());
      body.set(
          "{\"id\":\""
              + user
              + "\",\"email\":\"verified@fixture.invalid\",\"email_confirmed_at\":\""
              + Instant.now().minusSeconds(1)
              + "\"}");
      assertEquals(
          Optional.of("verified@fixture.invalid"), contacts.verifiedEmail("fixture-token", user));
      assertTrue(contacts.verifiedEmail("fixture-token", UUID.randomUUID()).isEmpty());
      body.set(
          "{\"id\":\""
              + user
              + "\",\"email\":\"unverified@fixture.invalid\",\"user_metadata\":{\"email_verified\":true}}");
      assertTrue(contacts.verifiedEmail("fixture-token", user).isEmpty());
    } finally {
      server.stop(0);
    }
  }
}
