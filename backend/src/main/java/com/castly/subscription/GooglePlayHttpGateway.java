package com.castly.subscription;

import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Pinned HTTPS endpoints; provider response bodies and tokens are never logged or surfaced. */
@Component
public class GooglePlayHttpGateway implements GooglePlayGateway {
  private final ObjectMapper json;
  private final String credentials;
  private final HttpClient http;
  private final URI publisher, oauth;
  private String accessToken;
  private Instant tokenExpiry = Instant.EPOCH;

  @org.springframework.beans.factory.annotation.Autowired
  public GooglePlayHttpGateway(
      ObjectMapper json, @Value("${castly.google.credentials-file:}") String credentials) {
    this(
        json,
        credentials,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        URI.create("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"),
        URI.create("https://oauth2.googleapis.com/token"));
  }

  // Package-private injection is for deterministic local HTTP contract tests only.
  GooglePlayHttpGateway(
      ObjectMapper json, String credentials, HttpClient http, URI publisher, URI oauth) {
    this.json = json;
    this.credentials = credentials;
    this.http = http;
    this.publisher = publisher;
    this.oauth = oauth;
  }

  public boolean configured() {
    try {
      if (credentials.isBlank()
          || !Files.isRegularFile(Path.of(credentials))
          || Files.size(Path.of(credentials)) > 65536) return false;
      var key = json.readTree(Files.readString(Path.of(credentials)));
      if (!"service_account".equals(key.path("type").asText())
          || !key.path("client_email").asText().endsWith(".gserviceaccount.com")) return false;
      String pem =
          key.path("private_key")
              .asText()
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      var rsa =
          (RSAPrivateKey)
              KeyFactory.getInstance("RSA")
                  .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      return rsa.getModulus().bitLength() >= 2048;
    } catch (Exception e) {
      return false;
    }
  }

  private static String segment(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private JsonNode request(HttpRequest request) {
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        byte[] bytes = input.readNBytes(262145);
        if (response.statusCode() < 200 || response.statusCode() >= 300 || bytes.length > 262144)
          throw unavailable();
        return bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes);
      }
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  private synchronized String access() {
    if (accessToken != null && tokenExpiry.isAfter(Instant.now().plusSeconds(60)))
      return accessToken;
    if (!configured()) throw unavailable();
    try {
      var key = json.readTree(Files.readString(Path.of(credentials)));
      if (!"service_account".equals(key.path("type").asText())) throw unavailable();
      String email = key.path("client_email").asText();
      String pem =
          key.path("private_key")
              .asText()
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      var rsa =
          (RSAPrivateKey)
              KeyFactory.getInstance("RSA")
                  .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(email)
              .audience("https://oauth2.googleapis.com/token")
              .claim("scope", "https://www.googleapis.com/auth/androidpublisher")
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(3600)))
              .build();
      var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      signed.sign(new RSASSASigner(rsa));
      var response =
          request(
              HttpRequest.newBuilder(oauth)
                  .timeout(Duration.ofSeconds(8))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="
                              + segment(signed.serialize())))
                  .build());
      accessToken = response.path("access_token").asText();
      int expires = response.path("expires_in").asInt();
      if (accessToken.isBlank() || expires < 60) throw unavailable();
      tokenExpiry = now.plusSeconds(Math.min(expires, 3600));
      return accessToken;
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  public JsonNode get(String packageName, String token) {
    return request(
        HttpRequest.newBuilder(
                publisher.resolve(
                    segment(packageName) + "/purchases/subscriptionsv2/tokens/" + segment(token)))
            .timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bearer " + access())
            .GET()
            .build());
  }

  public void acknowledge(String packageName, String product, String token) {
    request(
        HttpRequest.newBuilder(
                publisher.resolve(
                    segment(packageName)
                        + "/purchases/subscriptions/"
                        + segment(product)
                        + "/tokens/"
                        + segment(token)
                        + ":acknowledge"))
            .timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bearer " + access())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build());
  }

  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Google Play verification is temporarily unavailable. Retry or restore purchases.");
  }
}
