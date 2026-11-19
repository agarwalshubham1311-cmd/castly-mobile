package com.castly.trust;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TrustedContactServiceTest {
  @Test
  void onlyPlausibleTopLevelConfirmationTimestampsCount() throws Exception {
    var json = new ObjectMapper();
    assertFalse(
        TrustedContactService.confirmed(
            json.readTree(
                "{\"user_metadata\":{\"email_verified\":true,\"email_confirmed_at\":\"2026-01-01T00:00:00Z\"}}"),
            "email_confirmed_at"));
    for (String value :
        new String[] {
          "true",
          "123",
          "null",
          "\"Infinity\"",
          "\"1900-01-01T00:00:00Z\"",
          "\"2999-01-01T00:00:00Z\""
        })
      assertFalse(
          TrustedContactService.confirmed(
              json.readTree("{\"email_confirmed_at\":" + value + "}"), "email_confirmed_at"));
    assertTrue(
        TrustedContactService.confirmed(
            json.readTree("{\"email_confirmed_at\":\"2026-01-01T00:00:00Z\"}"),
            "email_confirmed_at"));
  }
}
