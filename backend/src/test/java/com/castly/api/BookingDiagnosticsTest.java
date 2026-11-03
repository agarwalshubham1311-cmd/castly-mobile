package com.castly.api;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

class BookingDiagnosticsTest {
  private DataAccessException failure(String state, String message) {
    return new DataAccessResourceFailureException(
        "outer SQL exception with sensitive parameters", new SQLException(message, state));
  }

  @Test
  void explicitGuardRejectionsAreAllowlistedSeparatelyFromEngineDenials() {
    Map<String, String> reasons =
        Map.of(
            "Booking participant required", "booking_guard_identity_or_participant",
            "Protected booking fields are immutable", "booking_guard_protected_fields",
            "Booking account role mismatch", "booking_guard_account_role",
            "Invalid booking transition", "booking_guard_transition",
            "permission denied for table sensitive_name", "postgres_privilege_denied",
            "new row violates row-level security policy for table private_table",
                "postgres_rls_denied");
    reasons.forEach(
        (message, reason) ->
            assertEquals(
                reason,
                SqlFailureKind.of(
                    failure("42501", "ERROR: " + message + "\nWhere: private context"))));
    assertEquals(
        "unclassified_42501", SqlFailureKind.of(failure("42501", "arbitrary sensitive message")));
    assertEquals(
        "unclassified_42501",
        SqlFailureKind.of(
            failure("42501", "ERROR: Booking account role mismatch additional text")));
    assertEquals("unclassified_42501", SqlFailureKind.of(failure("42501", null)));
    assertEquals(
        "other_database_error",
        SqlFailureKind.of(failure("23505", "Booking account role mismatch")));
  }

  @Test
  void logContainsOnlyFixedReasonsAndBooleansAndPublicForbiddenResponseIsPreserved() {
    String sensitive = "never-log-jwt-or-account-7828a6f8-fd89-4b10-adc5-d718671a62d0";
    var logger = (Logger) LoggerFactory.getLogger(ApiErrors.class);
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    try {
      var evidence =
          new BookingUpdateDiagnostics.Evidence("ok", true, true, false, false, true, true);
      var error =
          assertThrows(
              DatabaseStep.class,
              () ->
                  DatabaseStep.run(
                      "booking.accepted.update",
                      evidence,
                      () -> {
                        throw failure(
                            "42501", "ERROR: Booking account role mismatch\nWhere: " + sensitive);
                      }));
      var response = new ApiErrors().databaseStep(error);
      assertEquals(403, response.getStatus());
      assertEquals("Not permitted", response.getDetail());
      assertEquals(java.util.Set.of("operation", "reference"), response.getProperties().keySet());
      String line = capture.list.getLast().getFormattedMessage();
      assertTrue(line.contains("reason=booking_guard_account_role"));
      assertTrue(line.contains("identityMatches=true"));
      assertTrue(line.contains("accountVisible=false"));
      assertFalse(line.contains(sensitive));
      assertFalse(line.contains("outer SQL exception"));
      assertFalse(line.contains("Booking account role mismatch"));
      assertNull(capture.list.getLast().getThrowableProxy());
    } finally {
      logger.detachAppender(capture);
      capture.stop();
    }
  }

  @Test
  void disabledProbeDoesNotTouchTheDatabase() {
    assertNull(new BookingUpdateDiagnostics(false).capture(null, null, null));
  }
}
