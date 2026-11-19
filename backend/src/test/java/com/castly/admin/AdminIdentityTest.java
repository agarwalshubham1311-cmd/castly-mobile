package com.castly.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AdminIdentityTest {
  private Jwt token(String aal, Object amr, String sid) {
    return Jwt.withTokenValue("fixture")
        .header("alg", "ES256")
        .subject("00000000-0000-0000-0000-000000000031")
        .claim("aal", aal)
        .claim("amr", amr)
        .claim("session_id", sid)
        .build();
  }

  private final String sid = UUID.randomUUID().toString();

  @Test
  void passwordAmrDoesNotImplyMfa() {
    assertThrows(
        Exception.class,
        () ->
            AdminIdentity.from(
                token(
                    "aal2",
                    List.of(
                        Map.of("method", "password", "timestamp", Instant.now().getEpochSecond())),
                    sid)));
  }

  @Test
  void futureAndMissingMfaAreRejected() {
    for (Object amr :
        List.of(
            List.of(),
            List.of(
                Map.of(
                    "method", "totp", "timestamp", Instant.now().plusSeconds(60).getEpochSecond())),
            List.of(Map.of("method", "totp", "timestamp", "forged"))))
      assertThrows(Exception.class, () -> AdminIdentity.from(token("aal2", amr, sid)));
  }

  @Test
  void noSessionFixationAcrossAuthSessions() {
    assertThrows(
        Exception.class,
        () ->
            AdminIdentity.from(
                token(
                    "aal2",
                    List.of(Map.of("method", "totp", "timestamp", Instant.now().getEpochSecond())),
                    "not-a-session")));
  }

  @Test
  void freshestVerifiedAmrSupportsReauthentication() {
    var value =
        AdminIdentity.from(
            token(
                "aal2",
                List.of(
                    Map.of("method", "password", "timestamp", Instant.now().getEpochSecond()),
                    Map.of(
                        "method",
                        "totp",
                        "timestamp",
                        Instant.now().minusSeconds(600).getEpochSecond()),
                    Map.of("method", "totp", "timestamp", Instant.now().getEpochSecond())),
                sid));
    assertDoesNotThrow(value::recent);
  }

  @Test
  void productionFlagRequiresAuditSecret() {
    assertDoesNotThrow(() -> new AdminSettings(false, ""));
    assertThrows(IllegalStateException.class, () -> new AdminSettings(true, "short"));
  }
}
