package com.castly.trust;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class StorageEvidenceGatewayTest {
  @Test
  void callerJwtStorageContractBoundsUrlsAndConfirmsRealDeletion() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var json = new ObjectMapper();
    String path =
        "00000000-0000-0000-0000-000000000001/00000000-0000-0000-0000-000000000002/00000000-0000-0000-0000-000000000003.png";
    var mode = new AtomicReference<>("normal");
    var signedSeconds = new AtomicInteger();
    var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
    server.createContext(
        "/storage/v1/",
        exchange -> {
          try {
            assertEquals(
                "Bearer fixture-user-token",
                exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("sb_publishable_fixture", exchange.getRequestHeaders().getFirst("apikey"));
            String endpoint = exchange.getRequestURI().getPath();
            byte[] response;
            int status = 200;
            if (mode.get().equals("error")) {
              response = "{\"error\":\"fixture-secret-provider-details\"}".getBytes();
              status = 503;
            } else if (endpoint.contains("/object/sign/")) {
              int seconds = json.readTree(exchange.getRequestBody()).get("expiresIn").asInt();
              signedSeconds.set(seconds);
              Instant expiry =
                  Instant.now().plusSeconds(mode.get().equals("overlong") ? 300 : seconds);
              var jwt =
                  new com.nimbusds.jwt.SignedJWT(
                      new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256),
                      new com.nimbusds.jwt.JWTClaimsSet.Builder()
                          .expirationTime(Date.from(expiry))
                          .build());
              jwt.sign(new com.nimbusds.jose.crypto.MACSigner("01234567890123456789012345678901"));
              response =
                  json.writeValueAsBytes(
                      Map.of(
                          "signedURL",
                          "/object/sign/trust-evidence/" + path + "?token=" + jwt.serialize()));
            } else if (exchange.getRequestMethod().equals("DELETE")) {
              assertEquals(
                  path, json.readTree(exchange.getRequestBody()).get("prefixes").get(0).asText());
              response = json.writeValueAsBytes(List.of(Map.of("name", path)));
            } else {
              assertEquals("image/png", exchange.getRequestHeaders().getFirst("Content-Type"));
              assertArrayEquals(new byte[] {1, 2, 3}, exchange.getRequestBody().readAllBytes());
              response = "{}".getBytes();
            }
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
          } catch (Throwable failure) {
            failures.add(failure);
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var gateway =
          new StorageEvidenceGateway(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/v1",
              "sb_publishable_fixture",
              json);
      gateway.upload("fixture-user-token", path, new byte[] {1, 2, 3});
      String url = gateway.sign("fixture-user-token", path, Instant.now().plusSeconds(12));
      assertTrue(url.startsWith("http://127.0.0.1:"));
      assertTrue(signedSeconds.get() <= 12 && signedSeconds.get() > 0);
      gateway.remove("fixture-user-token", path);
      mode.set("overlong");
      assertThrows(
          org.springframework.web.server.ResponseStatusException.class,
          () -> gateway.sign("fixture-user-token", path, Instant.now().plusSeconds(5)));
      mode.set("error");
      var error =
          assertThrows(
              org.springframework.web.server.ResponseStatusException.class,
              () -> gateway.upload("fixture-user-token", path, new byte[] {1, 2, 3}));
      assertFalse(error.getMessage().contains("fixture-secret"));
      assertTrue(failures.isEmpty(), failures.toString());
    } finally {
      server.stop(0);
    }
  }
}
