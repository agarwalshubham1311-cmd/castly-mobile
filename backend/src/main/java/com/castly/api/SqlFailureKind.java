package com.castly.api;

import java.sql.SQLException;

/** Classify known failures; never return a database message or CONTEXT to logs or clients. */
public final class SqlFailureKind {
  private SqlFailureKind() {}

  public static String of(Throwable failure) {
    for (Throwable cause : causes(failure))
      if (cause instanceof java.net.UnknownHostException) return "dns_resolution_failed";
    SQLException sql = sqlException(failure);
    if (sql == null || !"42501".equals(sql.getSQLState())) return "other_database_error";
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

  public static String sqlState(Throwable failure) {
    SQLException sql = sqlException(failure);
    String state = sql == null ? null : sql.getSQLState();
    return state != null && state.matches("[A-Z0-9]{5}") ? state : "unknown";
  }

  private static SQLException sqlException(Throwable failure) {
    for (Throwable cause : causes(failure))
      if (cause instanceof SQLException sql && sql.getSQLState() != null) return sql;
    return null;
  }

  private static java.util.List<Throwable> causes(Throwable failure) {
    var result = new java.util.ArrayList<Throwable>();
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = failure;
        cause != null && result.size() < 32 && seen.add(cause);
        cause = cause.getCause()) result.add(cause);
    return result;
  }
}
