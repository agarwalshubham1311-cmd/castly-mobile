package com.castly.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Temporary opt-in read probe on the SAME transaction/connection as the status UPDATE. */
@Component
public class BookingUpdateDiagnostics {
  public record Evidence(
      String probe,
      Boolean identityPresent,
      Boolean identityMatches,
      Boolean accountVisible,
      Boolean roleMatches,
      Boolean callerIsTalent,
      Boolean priorRequested) {}

  private final boolean enabled;

  public BookingUpdateDiagnostics(
      @Value("${castly.booking-diagnostics.enabled:false}") boolean enabled) {
    this.enabled = enabled;
  }

  public Evidence capture(JdbcClient db, UUID expected, Map<String, Object> booking) {
    if (!enabled) return null;
    boolean talent = expected.equals(ActivityController.participantId(booking, "talent_id"));
    boolean requested = "requested".equals(booking.get("status"));
    // A failed read probe must not poison the transaction or replace the original UPDATE error.
    // Static savepoint name; never SQL/parameters or claims in diagnostics.
    db.sql("savepoint castly_booking_diagnostic").update();
    Evidence result;
    try {
      var row =
          db.sql(
                  "select auth.uid() is not null as identity_present,"
                      + " coalesce(auth.uid()=:expected,false) as identity_matches, exists(select 1"
                      + " from public.users where id=auth.uid()) as account_visible, exists(select"
                      + " 1 from public.users where id=auth.uid() and primary_role=cast(:role as"
                      + " public.account_role)) as role_matches")
              .param("expected", expected)
              .param("role", talent ? "talent" : "client")
              .query()
              .singleRow();
      result =
          new Evidence(
              "ok",
              (Boolean) row.get("identity_present"),
              (Boolean) row.get("identity_matches"),
              (Boolean) row.get("account_visible"),
              (Boolean) row.get("role_matches"),
              talent,
              requested);
    } catch (DataAccessException failure) {
      db.sql("rollback to savepoint castly_booking_diagnostic").update();
      result = new Evidence(SqlFailureKind.of(failure), null, null, null, null, talent, requested);
    }
    db.sql("release savepoint castly_booking_diagnostic").update();
    return result;
  }
}
