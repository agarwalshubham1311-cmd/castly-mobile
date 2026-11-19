package com.castly.trust;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class StorageEvidenceGateway implements EvidenceGateway {
  private final String base, key;
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public StorageEvidenceGateway(
      @Value("${castly.jwt.issuer}") String issuer,
      @Value("${castly.jwt.public-key:}") String key,
      ObjectMapper json) {
    URI uri = URI.create(issuer);
    boolean secure =
        "https".equals(uri.getScheme())
            || ("http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "::1").contains(uri.getHost()));
    if (!secure
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !uri.getPath().equals("/auth/v1"))
      throw new IllegalStateException("Evidence Storage requires the trusted Auth project origin");
    this.base = uri.getScheme() + "://" + uri.getRawAuthority() + "/storage/v1";
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
        throw new IllegalStateException("Evidence Storage requires a publishable or anon key");
    }
  }

  @Override
  public boolean configured() {
    return !key.isBlank();
  }

  private String path(String value) {
    if (!value.matches("[0-9a-f-]{36}/[0-9a-f-]{36}/[0-9a-f-]{36}\\.png"))
      throw new IllegalArgumentException("Invalid evidence object identity");
    return value;
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.BAD_GATEWAY,
        "Private evidence storage is temporarily unavailable. Retry safely.");
  }

  private HttpResponse<byte[]> request(
      String token, String method, String endpoint, byte[] body, String type) {
    if (!configured())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Private evidence storage is not configured");
    try {
      var builder =
          HttpRequest.newBuilder(URI.create(base + endpoint))
              .timeout(Duration.ofSeconds(15))
              .header("apikey", key)
              .header("Authorization", "Bearer " + token);
      if (type != null) builder.header("Content-Type", type);
      var response =
          http.send(
              builder
                  .method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofByteArray(body))
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        byte[] bytes = input.readNBytes(65537);
        if (bytes.length > 65536) throw unavailable();
        return new BoundedResponse(response, bytes);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception error) {
      if (error instanceof ResponseStatusException status) throw status;
      throw unavailable();
    }
  }

  private byte[] json(Object body) {
    try {
      return json.writeValueAsBytes(body);
    } catch (Exception invalid) {
      throw unavailable();
    }
  }

  @Override
  public void upload(String token, String path, byte[] png) {
    var response = request(token, "POST", "/object/trust-evidence/" + path(path), png, "image/png");
    if (response.statusCode() != 200 && response.statusCode() != 201) throw unavailable();
  }

  @Override
  public String sign(String token, String path, java.time.Instant deadline) {
    int seconds =
        (int)
            Math.min(
                60, java.time.Duration.between(java.time.Instant.now(), deadline).getSeconds());
    if (seconds < 1)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence access has expired");
    var response =
        request(
            token,
            "POST",
            "/object/sign/trust-evidence/" + path(path),
            json(Map.of("expiresIn", seconds)),
            "application/json");
    if (response.statusCode() != 200) throw unavailable();
    try {
      String value = json.readTree(response.body()).path("signedURL").asText();
      String expected = "/object/sign/trust-evidence/" + path(path) + "?";
      if (!value.startsWith(expected) || value.length() > 16000 || value.contains("#"))
        throw unavailable();
      URI candidate = URI.create(base + value);
      if (!candidate.getPath().equals("/storage/v1/object/sign/trust-evidence/" + path))
        throw unavailable();
      String signedToken =
          Arrays.stream(candidate.getRawQuery().split("&"))
              .filter(part -> part.startsWith("token="))
              .map(
                  part ->
                      java.net.URLDecoder.decode(
                          part.substring(6), java.nio.charset.StandardCharsets.UTF_8))
              .findFirst()
              .orElseThrow();
      var expiry =
          com.nimbusds.jwt.SignedJWT.parse(signedToken).getJWTClaimsSet().getExpirationTime();
      if (expiry == null
          || expiry.toInstant().isAfter(deadline)
          || !expiry.toInstant().isAfter(java.time.Instant.now())) throw unavailable();
      return candidate.toString();
    } catch (Exception error) {
      throw unavailable();
    }
  }

  @Override
  public void remove(String token, String path) {
    path(path);
    var response =
        request(
            token,
            "DELETE",
            "/object/trust-evidence",
            json(Map.of("prefixes", List.of(path))),
            "application/json");
    if (response.statusCode() != 200) throw unavailable();
    try {
      var result = json.readTree(response.body());
      if (result.isArray())
        for (var object : result) if (path.equals(object.path("name").asText())) return;
    } catch (Exception ignored) {
    }
    var exists = request(token, "GET", "/object/authenticated/trust-evidence/" + path, null, null);
    if (exists.statusCode() == 404) return;
    try {
      var error = json.readTree(exists.body());
      if (exists.statusCode() == 400
          && ("404".equals(error.path("statusCode").asText())
              || "not_found".equals(error.path("code").asText()))) return;
    } catch (Exception ignored) {
    }
    throw unavailable();
  }

  private record BoundedResponse(HttpResponse<?> source, byte[] body)
      implements HttpResponse<byte[]> {
    public int statusCode() {
      return source.statusCode();
    }

    public HttpRequest request() {
      return source.request();
    }

    public Optional<HttpResponse<byte[]>> previousResponse() {
      return Optional.empty();
    }

    public HttpHeaders headers() {
      return source.headers();
    }

    public Optional<javax.net.ssl.SSLSession> sslSession() {
      return source.sslSession();
    }

    public URI uri() {
      return source.uri();
    }

    public HttpClient.Version version() {
      return source.version();
    }
  }
}
