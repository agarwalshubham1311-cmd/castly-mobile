package com.castly.subscription;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.*;

class GoogleRtdnSignatureTest {
  @Test
  void actualBearerFilterRejectsForgedWrongAudienceAndUntrustedEmailBeforeDelivery()
      throws Exception {
    var trusted = new RSAKeyGenerator(2048).keyID("google-fixture").generate();
    var forged = new RSAKeyGenerator(2048).keyID("google-fixture").generate();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/jwks",
        exchange -> {
          byte[] bytes = new JWKSet(trusted.toPublicJWK()).toString().getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var decoder =
          GoogleRtdnSecurity.decoder(
              "https://castly.test/rtdn",
              "push@example.iam.gserviceaccount.com",
              "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
      var provider = new JwtAuthenticationProvider(decoder);
      var bearer = new BearerTokenAuthenticationFilter(provider::authenticate);
      var chain = new FilterChainProxy(new DefaultSecurityFilterChain(request -> true, bearer));
      for (String variant :
          List.of("valid", "forged", "audience", "email", "unverified", "issuer")) {
        SecurityContextHolder.clearContext();
        var claims =
            new JWTClaimsSet.Builder()
                .subject("google-service-subject")
                .issuer(
                    variant.equals("issuer")
                        ? "https://evil.example"
                        : "https://accounts.google.com")
                .audience(variant.equals("audience") ? "wrong" : "https://castly.test/rtdn")
                .claim(
                    "email",
                    variant.equals("email")
                        ? "other@example.com"
                        : "push@example.iam.gserviceaccount.com")
                .claim("email_verified", !variant.equals("unverified"))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        var jwt =
            new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("google-fixture").build(), claims);
        jwt.sign(new RSASSASigner(variant.equals("forged") ? forged : trusted));
        var request = new MockHttpServletRequest("POST", "/api/v1/subscriptions/google-play/rtdn");
        request.addHeader("Authorization", "Bearer " + jwt.serialize());
        var response = new MockHttpServletResponse();
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        chain.doFilter(
            request,
            response,
            (req, res) -> {
              invoked.set(true);
              ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204);
            });
        assertEquals(variant.equals("valid") ? 204 : 401, response.getStatus(), variant);
        assertEquals(variant.equals("valid"), invoked.get(), variant);
      }
    } finally {
      server.stop(0);
      SecurityContextHolder.clearContext();
    }
  }
}
