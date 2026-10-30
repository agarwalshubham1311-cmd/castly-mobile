package com.castly.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ActivityAuthorizationTest {
  @Test
  void participantChecksAcceptRawJdbcUuidsAndJsonStringsAndDenyOutsiders() {
    UUID client = UUID.randomUUID(), talent = UUID.randomUUID(), outsider = UUID.randomUUID();
    for (var row :
        List.<Map<String, Object>>of(
            Map.of("client_id", client, "talent_id", talent),
            Map.of("client_id", client.toString(), "talent_id", talent.toString()))) {
      assertDoesNotThrow(() -> ActivityController.requireParticipant(client, row));
      assertDoesNotThrow(() -> ActivityController.requireParticipant(talent, row));
      var denied =
          assertThrows(
              ResponseStatusException.class,
              () -> ActivityController.requireParticipant(outsider, row));
      assertEquals(404, denied.getStatusCode().value());
    }
  }
}
