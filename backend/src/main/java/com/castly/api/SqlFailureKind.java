package com.castly.api;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;

/** Classify known failures; never return a database message or CONTEXT to logs or clients. */
public final class SqlFailureKind {
  private SqlFailureKind() {}

  public static String of(DataAccessException failure) {
    if (!(failure.getMostSpecificCause() instanceof SQLException sql)
        || !"42501".equals(sql.getSQLState())) return "other_database_error";
    String message = sql.getMessage();
    if (message == null) return "unclassified_42501";
    String firstLine = message.split("\\R", 2)[0];
    if (firstLine.startsWith("ERROR: ")) firstLine = firstLine.substring(7);
    return switch (firstLine) {
      case "Booking participant required" -> "booking_guard_identity_or_participant";
      case "Protected booking fields are immutable" -> "booking_guard_protected_fields";
      case "Booking account role mismatch" -> "booking_guard_account_role";
      case "Invalid booking transition" -> "booking_guard_transition";
      default -> {
        if (firstLine.startsWith("permission denied for ")) yield "postgres_privilege_denied";
        if (firstLine.startsWith("new row violates row-level security policy")
            || firstLine.startsWith("query would be affected by row-level security policy"))
          yield "postgres_rls_denied";
        yield "unclassified_42501";
      }
    };
  }
}
