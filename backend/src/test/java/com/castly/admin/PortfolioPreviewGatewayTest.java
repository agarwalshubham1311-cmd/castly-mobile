package com.castly.admin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class PortfolioPreviewGatewayTest {
  static final String PATH = "00000000-0000-0000-0000-000000000001/portrait.jpg";
  HttpServer server;
  String issuer;
  AtomicReference<String> reply = new AtomicReference<>();
  AtomicInteger calls = new AtomicInteger();
  AtomicReference<String> authorization = new AtomicReference<>();
  AtomicReference<String> requestBody = new AtomicReference<>();
  AtomicInteger status = new AtomicInteger(200);

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/v1";
    server.createContext(
        "/storage/v1/object/sign/portfolio/",
        exchange -> {
          calls.incrementAndGet();
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Location", "https://untrusted.invalid/capture");
          exchange.sendResponseHeaders(status.get(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  PortfolioPreviewGateway gateway() {
    return new PortfolioPreviewGateway(issuer, "sb_publishable_fixture", new ObjectMapper());
  }

  static String signed(Instant expires) {
    var enc = Base64.getUrlEncoder().withoutPadding();
    return enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8))
        + "."
        + enc.encodeToString(
            ("{\"exp\":" + expires.getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8))
        + ".c2lnbmF0dXJl";
  }

  String response(String path, Instant until) {
    return "{\"signedURL\":\"/object/sign/portfolio/" + path + "?token=" + signed(until) + "\"}";
  }

  @Test
  void configuredPublicKeyAndTrustedOriginAreRequired() {
    assertThrows(
        IllegalStateException.class,
        () -> new PortfolioPreviewGateway(issuer, "sb_secret_forbidden", new ObjectMapper()));
    for (String bad :
        List.of(
            "http://external.invalid/auth/v1",
            "https://u:p@external.invalid/auth/v1",
            "https://external.invalid/wrong",
            "https:///auth/v1"))
      assertThrows(
          IllegalStateException.class,
          () -> new PortfolioPreviewGateway(bad, "", new ObjectMapper()));
    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                new PortfolioPreviewGateway(issuer, "", new ObjectMapper())
                    .sign("fixture", PATH, Instant.now().plusSeconds(60)));
    assertEquals(503, error.getStatusCode().value());
    assertEquals(0, calls.get());
  }

  @Test
  void authenticatedReviewerRequestProducesOnlyShortLivedExactPortfolioLink() {
    reply.set(response(PATH, Instant.now().plusSeconds(30)));
    var link = gateway().sign("synthetic-bearer", PATH, Instant.now().plusSeconds(60));
    assertTrue(
        link.get("url")
            .toString()
            .startsWith(
                issuer.replace("/auth/v1", "")
                    + "/storage/v1/object/sign/portfolio/"
                    + PATH
                    + "?token="));
    assertEquals("Bearer synthetic-bearer", authorization.get());
    try {
      int ttl = new ObjectMapper().readTree(requestBody.get()).path("expiresIn").asInt();
      assertTrue(ttl > 0 && ttl <= 60);
    } catch (Exception ex) {
      throw new AssertionError(ex);
    }
    assertTrue(((Number) link.get("expires_in")).longValue() <= 30);
    assertEquals(1, calls.get());
  }

  @Test
  void providerCannotRedirectChangeObjectOrReturnOverlongExpiredOrOutOfWindowCapabilities() {
    for (String value :
        List.of(
            "{\"signedURL\":\"https://untrusted.invalid/capture\"}",
            response(PATH + ".other", Instant.now().plusSeconds(30)),
            response(PATH, Instant.now().minusSeconds(1)),
            response(PATH, Instant.now().plusSeconds(600)),
            "{\"signedURL\":\"" + "x".repeat(65537) + "\"}")) {
      reply.set(value);
      assertThrows(
          ResponseStatusException.class,
          () -> gateway().sign("synthetic", PATH, Instant.now().plusSeconds(60)));
    }
    reply.set(response(PATH, Instant.now().plusSeconds(30)));
    status.set(302);
    int before = calls.get();
    assertThrows(
        ResponseStatusException.class,
        () -> gateway().sign("synthetic", PATH, Instant.now().plusSeconds(60)));
    assertEquals(before + 1, calls.get());
    status.set(200);
    assertThrows(
        ResponseStatusException.class,
        () -> gateway().sign("synthetic", PATH, Instant.now().plusSeconds(5)));
  }

  @Test
  void unsafePrivateMediaReferencesAndExpiredReviewWindowsNeverReachStorage() {
    for (String path :
        List.of(
            "private-media/file.pdf",
            "../private-media/file",
            PATH + "/../file",
            PATH + "\\file",
            PATH + "/%2e%2e/file"))
      assertThrows(
          ResponseStatusException.class,
          () -> gateway().sign("synthetic", path, Instant.now().plusSeconds(60)));
    assertThrows(
        ResponseStatusException.class,
        () -> gateway().sign("synthetic", PATH, Instant.now().minusSeconds(1)));
    assertEquals(0, calls.get());
  }
}
