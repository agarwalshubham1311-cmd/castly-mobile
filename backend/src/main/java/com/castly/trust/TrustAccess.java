package com.castly.trust;

import com.castly.api.ActorDatabase;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TrustAccess {
  private final ActorDatabase data;

  public TrustAccess(ActorDatabase data) {
    this.data = data;
  }

  public static void enabled(boolean flag) {
    if (!flag)
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "This safety feature is not available yet");
  }

  public static void reviewer(JdbcClient db, String scope) {
    if (!db.sql("select trust_private.is_reviewer(:s)")
        .param("s", scope)
        .query(Boolean.class)
        .single())
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Authorized safety reviewer access is required");
  }

  public static void lock(JdbcClient db, UUID id) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:id,47))")
        .param("id", id.toString())
        .query()
        .singleRow();
  }

  public static void audit(
      JdbcClient db,
      UUID actor,
      UUID subject,
      String scope,
      String action,
      UUID resource,
      String reason) {
    db.sql(
            "insert into"
                + " public.trust_audit_logs(actor_id,subject_id,scope,action,resource_id,reason)"
                + " values(:a,:s,:scope,:action,:r,:reason)")
        .param("a", actor)
        .param("s", subject)
        .param("scope", scope)
        .param("action", action)
        .param("r", resource)
        .param("reason", reason)
        .update();
  }

  public void rate(UUID actor, String operation, int max) {
    int count =
        data.as(
            actor,
            db ->
                db.sql(
                        "insert into"
                            + " trust_private.rate_limits(user_id,operation,window_start,attempts)"
                            + " values(:u,:o,date_trunc('minute',now()),1) on"
                            + " conflict(user_id,operation) do update set"
                            + " window_start=date_trunc('minute',now()),attempts=case when"
                            + " rate_limits.window_start=date_trunc('minute',now()) then"
                            + " rate_limits.attempts+1 else 1 end returning attempts")
                    .param("u", actor)
                    .param("o", operation)
                    .query(Integer.class)
                    .single());
    if (count > max)
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Retry in a minute.");
  }

  public static UUID id(Map<String, Object> row, String field) {
    return UUID.fromString(row.get(field).toString());
  }
}
