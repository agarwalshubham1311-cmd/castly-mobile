package com.castly.security;

import com.nimbusds.jwt.SignedJWT;
import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.*;

@Configuration
public class ApiSecurity {
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .cors(cors -> {})
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/talents/me")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/talents",
                        "/api/v1/talents/*",
                        "/api/v1/categories",
                        "/api/v1/portfolio",
                        "/api/v1/talents/*/portfolio")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (request, response, error) -> {
                          response.setStatus(401);
                          response.setHeader("WWW-Authenticate", "Bearer");
                          response.setContentType("application/problem+json");
                          response
                              .getWriter()
                              .write(
                                  "{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"A valid"
                                      + " Supabase access token is required\"}");
                        })
                    .accessDeniedHandler(
                        (request, response, error) -> {
                          response.setStatus(403);
                          response.setContentType("application/problem+json");
                          response
                              .getWriter()
                              .write(
                                  "{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"Not"
                                      + " permitted\"}");
                        }))
        .build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(@Value("${castly.cors-origins}") String origins) {
    var cors = new CorsConfiguration();
    var allowed =
        Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    if (allowed.isEmpty() || allowed.stream().anyMatch(s -> s.contains("*") || s.equals("null")))
      throw new IllegalStateException("Configure exact CORS origins");
    cors.setAllowedOrigins(allowed);
    cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    cors.setAllowCredentials(false);
    cors.setMaxAge(600L);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", cors);
    return source;
  }

  @Bean
  JwtDecoder jwtDecoder(
      @Value("${castly.jwt.issuer}") String issuer,
      @Value("${castly.jwt.audience}") String audience,
      @Value("${castly.jwt.mode}") String mode,
      @Value("${castly.jwt.public-key:}") String key) {
    return decoder(issuer, audience, mode, key);
  }

  public static JwtDecoder decoder(String issuer, String audience, String mode, String key) {
    URI uri = URI.create(issuer);
    boolean local = List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost());
    if ((!"https".equals(uri.getScheme()) && !(local && "http".equals(uri.getScheme())))
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !uri.getPath().endsWith("/auth/v1"))
      throw new IllegalStateException("Configure the exact HTTPS Supabase Auth issuer");
    if (audience.isBlank()) throw new IllegalStateException("JWT audience is required");
    var validator = validator(issuer, audience);
    if (mode.equals("jwks")) {
      var decoder =
          NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json")
              .jwsAlgorithms(
                  algs -> {
                    algs.clear();
                    algs.add(SignatureAlgorithm.RS256);
                    algs.add(SignatureAlgorithm.ES256);
                  })
              .build();
      decoder.setJwtValidator(validator);
      return decoder;
    }
    if (!mode.equals("supabase-auth"))
      throw new IllegalStateException("JWT_SIGNING_MODE must be jwks or supabase-auth");
    boolean publicKey = key.startsWith("sb_publishable_");
    try {
      publicKey |= "anon".equals(SignedJWT.parse(key).getJWTClaimsSet().getStringClaim("role"));
    } catch (Exception ignored) {
    }
    if (!publicKey)
      throw new IllegalStateException(
          "Legacy JWT validation requires a publishable/anon key, never service-role credentials");
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    var auth = RestClient.builder().requestFactory(factory).build();
    return token -> {
      try {
        var parsed = SignedJWT.parse(token);
        if (!Set.of("HS256", "ES256", "RS256")
            .contains(parsed.getHeader().getAlgorithm().getName()))
          throw new BadJwtException("Unsupported token algorithm");
        var claims = new HashMap<String, Object>(parsed.getJWTClaimsSet().getClaims());
        var expiration = parsed.getJWTClaimsSet().getExpirationTime();
        var issued = parsed.getJWTClaimsSet().getIssueTime();
        if (expiration == null) throw new BadJwtException("Missing expiration");
        var jwt =
            new Jwt(
                token,
                issued == null ? null : issued.toInstant(),
                expiration.toInstant(),
                parsed.getHeader().toJSONObject(),
                claims);
        var errors = validator.validate(jwt);
        if (errors.hasErrors())
          throw new JwtValidationException("Invalid access token", errors.getErrors());
        // The configured project's Auth server verifies the signature, including legacy HS256.
        var response =
            auth.get()
                .uri(issuer + "/user")
                .header("apikey", key)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(Map.class);
        if (response == null || !jwt.getSubject().equals(response.get("id")))
          throw new BadJwtException("Invalid subject");
        return jwt;
      } catch (JwtException e) {
        throw e;
      } catch (Exception e) {
        throw new BadJwtException("Access token could not be verified");
      }
    };
  }

  private static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
    OAuth2TokenValidator<Jwt> required =
        jwt -> {
          boolean valid;
          try {
            valid =
                jwt.getExpiresAt() != null
                    && jwt.getExpiresAt().isAfter(Instant.now())
                    && jwt.getAudience() != null
                    && jwt.getAudience().contains(audience)
                    && "authenticated".equals(jwt.getClaimAsString("role"));
            UUID.fromString(jwt.getSubject());
          } catch (Exception e) {
            valid = false;
          }
          return valid
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(
                  new OAuth2Error("invalid_token", "Invalid access token claims", null));
        };
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer), required);
  }
}
