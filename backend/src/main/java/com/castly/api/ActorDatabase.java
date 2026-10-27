package com.castly.api;

import java.sql.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** JWT identity is transaction-local: pooled connections cannot retain a previous user's claims. */
@Component
public class ActorDatabase implements InitializingBean {
  private final JdbcClient db;
  private final TransactionTemplate transactions;

  public ActorDatabase(DataSource source, PlatformTransactionManager manager) {
    db = JdbcClient.create(source);
    transactions = new TransactionTemplate(manager);
  }

  @Override
  public void afterPropertiesSet() {
    var role =
        db.sql(
                "select current_user as"
                    + " name,rolsuper,rolbypassrls,rolcreatedb,rolcreaterole,rolreplication,has_schema_privilege(current_user,'public','CREATE')"
                    + " as schema_create,has_database_privilege(current_user,current_database(),'CREATE')"
                    + " as db_create,exists(select 1 from pg_auth_members where member=r.oid) as"
                    + " memberships,exists(select 1 from pg_class where relowner=r.oid and relkind"
                    + " in ('r','p')) as owns_tables from pg_roles r where rolname=current_user")
            .query()
            .singleRow();
    if (!"castly_api".equals(role.get("name"))
        || java.util.stream.Stream.of(
                "rolsuper",
                "rolbypassrls",
                "rolcreatedb",
                "rolcreaterole",
                "rolreplication",
                "schema_create",
                "db_create",
                "memberships",
                "owns_tables")
            .anyMatch(key -> Boolean.TRUE.equals(role.get(key))))
      throw new IllegalStateException(
          "Database login must be the dedicated least-privilege castly_api role");
  }

  public <T> T as(UUID actor, Function<JdbcClient, T> operation) {
    return transactions.execute(
        status -> {
          db.sql(
                  "select"
                      + " set_config('request.jwt.claim.sub',:actor,true),set_config('request.jwt.claims',:claims,true),set_config('request.jwt.claim.role',:role,true)")
              .param("actor", actor == null ? "" : actor.toString())
              .param(
                  "claims",
                  actor == null ? "{}" : "{\"sub\":\"" + actor + "\",\"role\":\"authenticated\"}")
              .param("role", actor == null ? "anon" : "authenticated")
              .query()
              .singleRow();
          return operation.apply(db);
        });
  }

  public static List<Map<String, Object>> rows(JdbcClient.StatementSpec statement) {
    return statement.query((rs, n) -> row(rs)).list();
  }

  public static Map<String, Object> one(JdbcClient.StatementSpec statement) {
    var rows = rows(statement);
    if (rows.isEmpty())
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "Resource not found");
    return rows.getFirst();
  }

  private static Map<String, Object> row(ResultSet rs) throws SQLException {
    var row = new LinkedHashMap<String, Object>();
    var meta = rs.getMetaData();
    for (int i = 1; i <= meta.getColumnCount(); i++) {
      Object value = rs.getObject(i);
      if (value instanceof java.sql.Array array) {
        value = Arrays.asList((Object[]) array.getArray());
        array.free();
      } else if (value instanceof Timestamp timestamp) value = timestamp.toInstant().toString();
      else if (value instanceof java.sql.Date date) value = date.toLocalDate().toString();
      else if (value != null && meta.getColumnType(i) == Types.OTHER) value = value.toString();
      row.put(meta.getColumnLabel(i), value);
    }
    return row;
  }
}
