package com.castly.admin;

import com.castly.engagement.BoundedHttpBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Signs only consented portfolio references for the already-authorized reviewer, never private
 * media.
 */
@Component
public class PortfolioPreviewGateway {
  private final String base, key;
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public PortfolioPreviewGateway(
      @Value("${castly.jwt.issuer}") String issuer,
      @Value("${castly.jwt.public-key:}") String key,
      ObjectMapper json) {
    URI origin;
    try {
      origin = URI.create(issuer);
    } catch (Exception invalid) {
      throw new IllegalStateException("Portfolio storage requires the trusted Auth project origin");
    }
    if (origin.getHost() == null
        || !(origin.getScheme().equals("https")
            || (origin.getScheme().equals("http")
                && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(origin.getHost())))
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !origin.getPath().equals("/auth/v1"))
      throw new IllegalStateException("Portfolio storage requires the trusted Auth project origin");
    this.base = origin.getScheme() + "://" + origin.getRawAuthority() + "/storage/v1";
    this.key = key;
    this.json = json;
    if (!key.isBlank()) {
      boolean allowed = key.startsWith("sb_publishable_");
      try {
        allowed |=
            "anon"
                .equals(
                    com.nimbusds.jwt.SignedJWT.parse(key).getJWTClaimsSet().getStringClaim("role"));
      } catch (Exception ignored) {
      }
      if (!allowed)
        throw new IllegalStateException("Portfolio storage requires a publishable or anon key");
    }
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "Portfolio image preview is unavailable. Retry safely.");
  }

  public Map<String, Object> sign(String bearer, String path, Instant deadline) {
    if (key.isBlank()) throw unavailable();
    if (path == null
        || path.length() > 512
        || path.contains("\\")
        || path.contains("%")
        || !path.matches("[0-9a-f-]{36}/[^\\p{Cntrl}]+")
        || Arrays.stream(path.split("/", -1))
            .anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid portfolio reference");
    int seconds = (int) Math.min(60, Duration.between(Instant.now(), deadline).getSeconds());
    if (seconds < 1)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review evidence access expired");
    try {
      String endpoint = new URI(null, null, "/object/sign/portfolio/" + path, null).toASCIIString();
      var request =
          HttpRequest.newBuilder(URI.create(base + endpoint))
              .timeout(Duration.ofSeconds(5))
              .header("apikey", key)
              .header("Authorization", "Bearer " + bearer)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{\"expiresIn\":" + seconds + "}"))
              .build();
      var response = BoundedHttpBody.send(http, request, Duration.ofSeconds(5));
      if (response.statusCode() != 200) throw unavailable();
      String value = json.readTree(response.body()).path("signedURL").asText();
      if (value.length() > 16000 || !value.startsWith(endpoint + "?token=") || value.contains("#"))
        throw unavailable();
      URI candidate = URI.create(base + value);
      if (!candidate.getPath().equals("/storage/v1/object/sign/portfolio/" + path)
          || !candidate.getRawQuery().matches("token=[A-Za-z0-9_.-]+")) throw unavailable();
      var expires =
          com.nimbusds.jwt.SignedJWT.parse(candidate.getRawQuery().substring(6))
              .getJWTClaimsSet()
              .getExpirationTime();
      Instant now = Instant.now();
      if (expires == null
          || !expires.toInstant().isAfter(now)
          || expires.toInstant().isAfter(now.plusSeconds(60))
          || expires.toInstant().isAfter(deadline)) throw unavailable();
      return Map.of(
          "url",
          candidate.toString(),
          "expires_in",
          Math.max(1, Duration.between(now, expires.toInstant()).toSeconds()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception error) {
      if (error instanceof ResponseStatusException status) throw status;
      throw unavailable();
    }
  }
}
