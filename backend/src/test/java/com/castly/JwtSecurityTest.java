package com.castly;

import static org.junit.jupiter.api.Assertions.*;

import com.castly.security.ApiSecurity;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;

class JwtSecurityTest {
  private HttpServer server;
  private String issuer;
  private RSAKey rsa;
  private ECKey ec;
  private String legacy;
  private String authUser = SUBJECT;
  private int authStatus = 200;
  private final java.util.concurrent.atomic.AtomicInteger authCalls =
      new java.util.concurrent.atomic.AtomicInteger();
  private static final String SUBJECT = "00000000-0000-0000-0000-000000000001";

  @BeforeEach
  void setup() throws Exception {
    rsa = new RSAKeyGenerator(2048).keyID("rsa").generate();
    ec = new ECKeyGenerator(Curve.P_256).keyID("ec").generate();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/v1";
    server.createContext(
        "/auth/v1/.well-known/jwks.json",
        exchange -> {
          byte[] body =
              new JWKSet(List.of(rsa.toPublicJWK(), ec.toPublicJWK()))
                  .toString()
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/auth/v1/user",
        exchange -> {
          authCalls.incrementAndGet();
          boolean valid =
              ("Bearer " + legacy).equals(exchange.getRequestHeaders().getFirst("Authorization"))
                  && "sb_publishable_test".equals(exchange.getRequestHeaders().getFirst("apikey"));
          byte[] body =
              (valid ? "{\"id\":\"" + authUser + "\"}" : "{}").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(valid ? authStatus : 401, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String token(
      JWSAlgorithm algorithm, String iss, String audience, Instant expires, String role)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .subject(SUBJECT)
            .issuer(iss)
            .audience(audience)
            .claim("role", role)
            .issueTime(Date.from(Instant.now().minusSeconds(20)));
    if (expires != null) claims.expirationTime(Date.from(expires));
    String kid = algorithm.equals(JWSAlgorithm.RS256) ? "rsa" : "ec";
    var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(kid).build(), claims.build());
    jwt.sign(
        algorithm.equals(JWSAlgorithm.RS256)
            ? new RSASSASigner(rsa)
            : algorithm.equals(JWSAlgorithm.ES256)
                ? new ECDSASigner(ec)
                : new MACSigner("01234567890123456789012345678901"));
    return jwt.serialize();
  }

  @Test
  void verifiesRsaAndEcSignatures() throws Exception {
    var decoder = ApiSecurity.decoder(issuer, "authenticated", "jwks", "");
    for (var alg : List.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256))
      assertEquals(
          SUBJECT,
          decoder
              .decode(
                  token(
                      alg,
                      issuer,
                      "authenticated",
                      Instant.now().plusSeconds(300),
                      "authenticated"))
              .getSubject());
  }

  @Test
  void rejectsForgedSignature() throws Exception {
    var raw =
        token(
            JWSAlgorithm.RS256,
            issuer,
            "authenticated",
            Instant.now().plusSeconds(300),
            "authenticated");
    var parsed = SignedJWT.parse(raw);
    parsed = new SignedJWT(parsed.getHeader(), parsed.getJWTClaimsSet());
    parsed.sign(new RSASSASigner(new RSAKeyGenerator(2048).generate()));
    String forged = parsed.serialize();
    assertThrows(
        JwtException.class,
        () -> ApiSecurity.decoder(issuer, "authenticated", "jwks", "").decode(forged));
  }

  @Test
  void rejectsWrongIssuerAudienceExpiryRoleAndMissingExpiry() throws Exception {
    var decoder = ApiSecurity.decoder(issuer, "authenticated", "jwks", "");
    for (String raw :
        List.of(
            token(
                JWSAlgorithm.RS256,
                "https://wrong.example/auth/v1",
                "authenticated",
                Instant.now().plusSeconds(300),
                "authenticated"),
            token(
                JWSAlgorithm.RS256,
                issuer,
                "wrong",
                Instant.now().plusSeconds(300),
                "authenticated"),
            token(
                JWSAlgorithm.RS256,
                issuer,
                "authenticated",
                Instant.now().minusSeconds(1),
                "authenticated"),
            token(
                JWSAlgorithm.RS256,
                issuer,
                "authenticated",
                Instant.now().plusSeconds(300),
                "service_role"),
            token(JWSAlgorithm.RS256, issuer, "authenticated", null, "authenticated")))
      assertThrows(JwtException.class, () -> decoder.decode(raw));
  }

  @Test
  void rejectsHmacDowngradeInJwksMode() throws Exception {
    String raw =
        token(
            JWSAlgorithm.HS256,
            issuer,
            "authenticated",
            Instant.now().plusSeconds(300),
            "authenticated");
    assertThrows(
        JwtException.class,
        () -> ApiSecurity.decoder(issuer, "authenticated", "jwks", "").decode(raw));
  }

  @Test
  void verifiesLegacyViaConfiguredAuthServerWithoutSigningSecret() throws Exception {
    legacy =
        token(
            JWSAlgorithm.HS256,
            issuer,
            "authenticated",
            Instant.now().plusSeconds(300),
            "authenticated");
    var decoder =
        ApiSecurity.decoder(issuer, "authenticated", "supabase-auth", "sb_publishable_test");
    assertEquals(SUBJECT, decoder.decode(legacy).getSubject());
    String forged =
        token(
            JWSAlgorithm.HS256,
            issuer,
            "authenticated",
            Instant.now().plusSeconds(250),
            "authenticated");
    assertThrows(JwtException.class, () -> decoder.decode(forged));
  }

  @Test
  void rejectsUntrustedIssuerAndPrivilegedKeyConfiguration() {
    assertThrows(
        IllegalStateException.class,
        () -> ApiSecurity.decoder("http://untrusted.example/auth/v1", "authenticated", "jwks", ""));
    assertThrows(
        IllegalStateException.class,
        () -> ApiSecurity.decoder(issuer, "authenticated", "supabase-auth", "sb_secret_invalid"));
  }

  @Test
  void authModeRejectsInvalidClaimsBeforeContactingProvider() throws Exception {
    var decoder =
        ApiSecurity.decoder(issuer, "authenticated", "supabase-auth", "sb_publishable_test");
    for (var alg : List.of(JWSAlgorithm.HS256, JWSAlgorithm.ES256, JWSAlgorithm.RS256)) {
      for (var raw :
          List.of(
              token(alg, issuer, "wrong", Instant.now().plusSeconds(300), "authenticated"),
              token(
                  alg,
                  "https://other.example/auth/v1",
                  "authenticated",
                  Instant.now().plusSeconds(300),
                  "authenticated"),
              token(alg, issuer, "authenticated", Instant.now().minusSeconds(1), "authenticated"),
              token(alg, issuer, "authenticated", null, "authenticated"),
              token(alg, issuer, "authenticated", Instant.now().plusSeconds(300), "anon"))) {
        legacy = raw;
        assertThrows(JwtException.class, () -> decoder.decode(raw));
      }
    }
    assertEquals(0, authCalls.get());
  }

  @Test
  void authModeSupportsAsymmetricOverlapAndFailsClosedForWrongUserAndProviderError()
      throws Exception {
    var decoder =
        ApiSecurity.decoder(issuer, "authenticated", "supabase-auth", "sb_publishable_test");
    for (var alg : List.of(JWSAlgorithm.ES256, JWSAlgorithm.RS256)) {
      legacy = token(alg, issuer, "authenticated", Instant.now().plusSeconds(300), "authenticated");
      assertEquals(SUBJECT, decoder.decode(legacy).getSubject());
    }
    authUser = UUID.randomUUID().toString();
    assertThrows(JwtException.class, () -> decoder.decode(legacy));
    authUser = SUBJECT;
    authStatus = 503;
    assertThrows(JwtException.class, () -> decoder.decode(legacy));
  }

  @Test
  void rejectsMalformedAndInvalidSubjectAndFutureNotBeforeInBothModes() throws Exception {
    for (String mode : List.of("jwks", "supabase-auth")) {
      var decoder = ApiSecurity.decoder(issuer, "authenticated", mode, "sb_publishable_test");
      assertThrows(JwtException.class, () -> decoder.decode("malformed-token"));
      for (boolean badSubject : List.of(true, false)) {
        var claims =
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience("authenticated")
                .claim("role", "authenticated")
                .subject(badSubject ? "not-a-uuid" : SUBJECT)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(600)));
        if (!badSubject) claims.notBeforeTime(Date.from(Instant.now().plusSeconds(300)));
        var signed =
            new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rsa").build(), claims.build());
        signed.sign(new RSASSASigner(rsa));
        legacy = signed.serialize();
        assertThrows(JwtException.class, () -> decoder.decode(legacy));
      }
    }
    assertThrows(
        IllegalStateException.class,
        () -> ApiSecurity.decoder(issuer, "authenticated", "unknown", ""));
  }
}
