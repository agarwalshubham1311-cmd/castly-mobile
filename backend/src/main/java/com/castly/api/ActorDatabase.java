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
  private com.castly.engagement.Phase6Settings phase6 = new com.castly.engagement.Phase6Settings();

  @org.springframework.beans.factory.annotation.Autowired
  public void phase6(com.castly.engagement.Phase6Settings settings) {
    phase6 = settings;
  }

  private final TransactionTemplate transactions;

  public ActorDatabase(DataSource source, PlatformTransactionManager manager) {
    db = JdbcClient.create(source);
    transactions = new TransactionTemplate(manager);
  }

  @Override
  public void afterPropertiesSet() {
    DatabaseRoleSafety.requireRestricted(db.sql(DatabaseRoleSafety.QUERY).query().singleRow());
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
          db.sql(
                  "select"
                      + " set_config('castly.phase6.push',:push,true),set_config('castly.phase6.email',:email,true),set_config('castly.phase6.engagement',:engagement,true),set_config('castly.phase6.analytics',:analytics,true),set_config('castly.phase6.subject',:subject,true)")
              .param("push", Boolean.toString(phase6.push))
              .param("email", Boolean.toString(phase6.email))
              .param("engagement", Boolean.toString(phase6.engagement))
              .param("analytics", Boolean.toString(phase6.analytics))
              .param("subject", phase6.subject(actor))
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
