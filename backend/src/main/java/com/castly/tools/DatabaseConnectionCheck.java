package com.castly.tools;

import com.castly.api.DatabaseConfiguration;
import com.castly.api.DatabaseRoleSafety;
import com.castly.api.NotificationService;
import com.castly.api.SqlFailureKind;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Explicit operator command only: metadata reads, no Spring startup, migrations or business writes.
 */
public final class DatabaseConnectionCheck {
  private DatabaseConnectionCheck() {}

  public static Map<String, Object> inspect(Connection connection) throws Exception {
    connection.setReadOnly(true);
    connection.setAutoCommit(false);
    try {
      var db = JdbcClient.create(new SingleConnectionDataSource(connection, true));
      DatabaseRoleSafety.requireRestricted(db.sql(DatabaseRoleSafety.QUERY).query().singleRow());
      var identity =
          db.sql(
                  "select exists(select 1 from pg_proc p join pg_namespace n on"
                      + " n.oid=p.pronamespace where n.nspname='private' and"
                      + " p.proname='request_user_id' and p.pronargs=0 and not p.prosecdef and"
                      + " p.provolatile='s' and p.prorettype='uuid'::regtype) as ready")
              .query(Boolean.class)
              .single();
      if (!identity) throw new IllegalStateException("Private invoker identity is not ready");
      if (!db.sql("select private.request_user_id() is null").query(Boolean.class).single())
        throw new IllegalStateException("Unexpected identity on fresh diagnostic connection");
      boolean tls =
          db.sql("select coalesce((select ssl from pg_stat_ssl where pid=pg_backend_pid()),false)")
              .query(Boolean.class)
              .single();
      return Map.of(
          "role_verified",
          true,
          "private_identity_ready",
          true,
          "read_only",
          connection.isReadOnly()
              && db.sql("select current_setting('transaction_read_only')='on'")
                  .query(Boolean.class)
                  .single(),
          "server_backend_tls",
          tls,
          "notification_schema_ready",
          new NotificationService(true).available(db));
    } finally {
      connection.rollback();
    }
  }

  public static void main(String[] args) {
    try {
      String url = required("DATABASE_URL"),
          user = required("DATABASE_USERNAME"),
          password = required("DATABASE_PASSWORD");
      DatabaseConfiguration.validate(url, user);
      try (var connection = DriverManager.getConnection(url, user, password)) {
        var result = inspect(connection);
        boolean enabled =
            Boolean.parseBoolean(
                System.getenv().getOrDefault("CASTLY_NOTIFICATIONS_ENABLED", "false"));
        if (enabled && !Boolean.TRUE.equals(result.get("notification_schema_ready")))
          throw new IllegalStateException("Notification flag enabled before schema is ready");
        var uri = URI.create(url.substring(5));
        String endpoint =
            uri.getHost() != null && uri.getHost().endsWith(".pooler.supabase.com")
                ? (uri.getPort() == 5432
                    ? "session_pooler"
                    : uri.getPort() == 6543 ? "transaction_pooler" : "pooler_check_port")
                : "direct_or_custom";
        System.out.println(
            "PASS database connection endpoint="
                + endpoint
                + " checks="
                + result
                + " notifications_flag_enabled="
                + enabled);
        // server_backend_tls describes the DB-side socket, not client-to-pooler TLS.
      }
    } catch (Exception error) {
      System.err.println(
          "FAIL database connection reason="
              + SqlFailureKind.of(error)
              + " sqlstate="
              + SqlFailureKind.sqlState(error)
              + " exception_class="
              + error.getClass().getSimpleName());
      System.exit(1);
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing required database configuration");
    return value;
  }
}
