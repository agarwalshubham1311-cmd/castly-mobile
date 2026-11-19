package com.castly.admin;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Only verified resource-server JWTs reach this boundary. Never user_metadata roles/MFA. */
public record AdminIdentity(UUID user, UUID authSession, Instant mfaAt) {
  public static AdminIdentity from(Jwt jwt) {
    try {
      if (!"aal2".equals(jwt.getClaimAsString("aal"))) throw new IllegalArgumentException();
      UUID user = UUID.fromString(jwt.getSubject());
      UUID session = UUID.fromString(jwt.getClaimAsString("session_id"));
      Instant verified = null;
      Object methods = jwt.getClaims().get("amr");
      if (methods instanceof List<?> list)
        for (Object value : list)
          if (value instanceof Map<?, ?> amr
              && Set.of("totp", "mfa/totp").contains(amr.get("method"))
              && amr.get("timestamp") instanceof Number timestamp) {
            Instant time = Instant.ofEpochSecond(timestamp.longValue());
            if (!time.isAfter(Instant.now().plusSeconds(30))
                && (verified == null || time.isAfter(verified))) verified = time;
          }
      if (verified == null) throw new IllegalArgumentException();
      return new AdminIdentity(user, session, verified);
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Verified administrator MFA required");
    }
  }

  public void recent() {
    if (mfaAt.isBefore(Instant.now().minusSeconds(300)))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Reauthenticate with MFA to continue");
  }
}
