package com.castly.trust;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TrustedContactService {
  private final String issuer, key;
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public TrustedContactService(
      @Value("${castly.jwt.issuer}") String issuer,
      @Value("${castly.jwt.public-key:}") String key,
      ObjectMapper json) {
    this.issuer = issuer;
    this.key = key;
    this.json = json;
    if (!key.isBlank()) {
      boolean publicKey = key.startsWith("sb_publishable_");
      try {
        publicKey |=
            "anon"
                .equals(
                    com.nimbusds.jwt.SignedJWT.parse(key).getJWTClaimsSet().getStringClaim("role"));
      } catch (Exception ignored) {
      }
      if (!publicKey)
        throw new IllegalStateException(
            "Trusted contact lookup requires a publishable or anon key");
    }
  }

  public Map<String, Object> contact(String token, UUID actor) {
    var result = new LinkedHashMap<String, Object>();
    result.put("email_verified", null);
    result.put("phone_verified", null);
    result.put("status", "unavailable");
    if (key.isBlank()) return result;
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(issuer + "/user"))
                  .timeout(Duration.ofSeconds(5))
                  .header("apikey", key)
                  .header("Authorization", "Bearer " + token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        byte[] bytes = input.readNBytes(65537);
        if (response.statusCode() != 200 || bytes.length > 65536) return result;
        var user = json.readTree(bytes);
        if (!actor.toString().equals(user.path("id").asText())) return result;
        result.put("email_verified", confirmed(user, "email_confirmed_at"));
        result.put("phone_verified", confirmed(user, "phone_confirmed_at"));
        result.put("status", "verified_provider_response");
        return result;
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return result;
    }
  }

  public Optional<String> verifiedEmail(String token, UUID actor) {
    if (key.isBlank()) return Optional.empty();
    try {
      var response =
          com.castly.engagement.BoundedHttpBody.send(
              http,
              HttpRequest.newBuilder(URI.create(issuer + "/user"))
                  .timeout(Duration.ofSeconds(5))
                  .header("apikey", key)
                  .header("Authorization", "Bearer " + token)
                  .GET()
                  .build(),
              Duration.ofSeconds(5));
      byte[] bytes = response.body();
      if (response.statusCode() != 200 || bytes.length > 65536) return Optional.empty();
      var user = json.readTree(bytes);
      String address = user.path("email").asText("");
      if (actor.toString().equals(user.path("id").asText())
          && confirmed(user, "email_confirmed_at")
          && address.length() <= 254
          && address.matches("[^\\s<>]+@[^\\s<>]+\\.[^\\s<>]+")) return Optional.of(address);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    }
    return Optional.empty();
  }

  static boolean confirmed(JsonNode user, String field) {
    try {
      return user.path(field).isTextual()
          && Instant.parse(user.path(field).asText()).isAfter(Instant.parse("2000-01-01T00:00:00Z"))
          && !Instant.parse(user.path(field).asText()).isAfter(Instant.now().plusSeconds(60));
    } catch (Exception e) {
      return false;
    }
  }
}
