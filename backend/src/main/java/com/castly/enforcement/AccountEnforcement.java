package com.castly.enforcement;

import static com.castly.api.ActorDatabase.*;

import com.castly.api.ActorDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Persisted restrictions survive switching off administrative write controls. */
@Service
public class AccountEnforcement implements InitializingBean {
  private final ActorDatabase data;
  private final ObjectMapper json;
  private final boolean enabled;
  private boolean installed;

  public AccountEnforcement(
      ActorDatabase data,
      ObjectMapper json,
      @Value("${castly.admin.enforcement-enabled:false}") boolean enabled) {
    this.data = data;
    this.json = json;
    this.enabled = enabled;
  }

  public void afterPropertiesSet() {
    installed =
        data.as(
            null,
            db ->
                db.sql("select to_regclass('enforcement_private.commands') is not null")
                    .query(Boolean.class)
                    .single());
    if (enabled && !installed)
      throw new IllegalStateException("Reviewed M7.2 enforcement migration required");
  }

  public void requireEnabled() {
    if (!enabled || !installed) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  public String stateFilter() {
    return installed
        ? "exists(select 1 from enforcement_private.accounts e where e.user_id=u.user_id and"
            + " e.state=:state and (e.until_at is null or e.until_at>clock_timestamp()))"
        : "false";
  }

  public String stateField(String id) {
    return installed
        ? "coalesce((select case when e.until_at<=clock_timestamp() then 'ACTIVE' else e.state end"
            + " from enforcement_private.accounts e where e.user_id="
            + id
            + "),'ACTIVE')"
        : "'ACTIVE'";
  }

  public boolean purchaseAllowed(JdbcClient db, UUID actor) {
    return !installed
        || db.sql("select enforcement_private.allowed(:u,'subscription_purchase')")
            .param("u", actor)
            .query(Boolean.class)
            .single();
  }

  public String discovery(String id) {
    return installed ? " and enforcement_private.visible(" + id + ") " : " ";
  }

  public String messagingField() {
    return installed
        ? " and enforcement_private.allowed(enforcement_private.actor(),'messaging') "
        : " ";
  }

  public void purchase(UUID actor) {
    if (installed)
      data.as(
          actor,
          db -> {
            db.sql("select enforcement_private.require_allowed(:u,'subscription_purchase')")
                .param("u", actor)
                .query()
                .singleRow();
            return null;
          });
  }

  public Map<String, Object> status(JdbcClient db, UUID user, boolean staff) {
    var result = new LinkedHashMap<String, Object>();
    if (!installed) {
      result.put("state", "ACTIVE");
      result.put("available", false);
      return result;
    }
    var states =
        rows(
            db.sql(
                    "select user_id,version,case when until_at<=clock_timestamp() then 'ACTIVE'"
                        + " else state end as state,case when until_at<=clock_timestamp() then"
                        + " array[]::text[] else denied end as"
                        + " denied,until_at,decision_id,explanation,reason_code,investigation from"
                        + " enforcement_private.accounts where user_id=:u")
                .param("u", user));
    result.putAll(
        states.isEmpty()
            ? Map.of("user_id", user, "version", 0, "state", "ACTIVE", "denied", List.of())
            : states.getFirst());
    result.put("available", true);
    if (!staff) result.remove("investigation");
    result.put(
        "decisions",
        rows(
            db.sql(
                    "select id,state,denied,until_at,reason_code,explanation,notified_at from"
                        + " enforcement_private.decisions where user_id=:u order by notified_at"
                        + " desc,id limit 25")
                .param("u", user)));
    result.put(
        "appeals",
        rows(
            db.sql(
                    "select"
                        + " id,decision_id,status,version,statement,evidence_ref,resolution,submitted_at,due_at,reopen_evidence,(status"
                        + " in('SUBMITTED','UNDER_REVIEW') and due_at<clock_timestamp()) as overdue"
                        + " from enforcement_private.appeals where user_id=:u order by id desc"
                        + " limit 25")
                .param("u", user)));
    result.put(
        "events",
        rows(
            db.sql(
                    "select id,operation,created_at,public_data from enforcement_private.events"
                        + " where user_id=:u order by id desc limit 50")
                .param("u", user)));
    if (staff) {
      result.put(
          "escalations",
          rows(
              db.sql(
                      "select id,created_at,payload->>'notes' as notes from"
                          + " enforcement_private.commands where user_id=:u and"
                          + " operation='ESCALATE' order by created_at desc,id limit 25")
                  .param("u", user)));

      result.put(
          "proposals",
          rows(
              db.sql(
                      "select"
                          + " id,base_version,action,state,created_at,proposer_id,appeal_id,payload->>'explanation'"
                          + " as explanation,payload->>'reason_code' as reason_code from"
                          + " enforcement_private.proposals where user_id=:u order by id limit 25")
                  .param("u", user)));
      result.put(
          "notes",
          rows(
              db.sql(
                      "select n.decision_id,n.notes,d.evidence_ref from enforcement_private.notes n"
                          + " join enforcement_private.decisions d on d.id=n.decision_id where"
                          + " d.user_id=:u order by d.notified_at desc limit 25")
                  .param("u", user)));
    } else
      result.put(
          "notices",
          rows(
              db.sql(
                      "select id,kind,explanation,created_at from enforcement_private.notices where"
                          + " user_id=:u order by created_at desc,id limit 25")
                  .param("u", user)));
    return result;
  }

  public Map<String, Object> command(
      JdbcClient db, UUID user, UUID key, String operation, Map<String, Object> payload) {
    if (!installed) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    try {
      db.sql(
              "insert into enforcement_private.commands(request_key,operation,user_id,payload)"
                  + " values(:k,:o,:u,cast(:p as jsonb))")
          .param("k", key)
          .param("o", operation)
          .param("u", user)
          .param("p", json.writeValueAsString(payload))
          .update();
      String result =
          db.sql(
                  "select result::text from enforcement_private.commands where"
                      + " actor_id=enforcement_private.actor() and request_key=:k")
              .param("k", key)
              .query(String.class)
              .single();
      return json.readValue(
          result, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Invalid account command", e);
    }
  }

  public List<Map<String, Object>> queue(JdbcClient db, String kind, UUID after, int limit) {
    String sql =
        switch (kind) {
          case "appeals" ->
              "select"
                  + " id,user_id,decision_id,status,version,due_at,reopen_evidence,(due_at<clock_timestamp())"
                  + " as overdue from enforcement_private.appeals where id>:a and (status"
                  + " in('SUBMITTED','UNDER_REVIEW') or reopen_evidence is not null) order by id"
                  + " limit :n";
          case "overdue" ->
              "select id,user_id,decision_id,status,version,due_at,true as overdue from"
                  + " enforcement_private.appeals where id>:a and status"
                  + " in('SUBMITTED','UNDER_REVIEW') and due_at<clock_timestamp() order by id limit"
                  + " :n";
          case "approvals" ->
              "select id,user_id,base_version,action,proposer_id,created_at,appeal_id from"
                  + " enforcement_private.proposals p where state='PENDING' and id>:a and"
                  + " base_version=(select version from enforcement_private.accounts c where"
                  + " c.user_id=p.user_id) and (appeal_id is null or exists(select 1 from"
                  + " enforcement_private.appeals a where a.id=p.appeal_id and"
                  + " a.status='UNDER_REVIEW')) order by id limit :n";
          default -> throw new IllegalArgumentException("Invalid queue");
        };
    return rows(db.sql(sql).param("a", after).param("n", limit));
  }
}
