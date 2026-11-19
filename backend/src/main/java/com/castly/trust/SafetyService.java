package com.castly.trust;

import static com.castly.api.ActorDatabase.*;

import com.castly.api.ActorDatabase;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SafetyService {
  private final ActorDatabase data;
  private final TrustSignals signals;
  private final TrustAccess access;
  private final boolean enabled;
  private final TrustSchema schema;

  public SafetyService(
      ActorDatabase data,
      TrustAccess access,
      TrustSchema schema,
      TrustSignals signals,
      @Value("${castly.trust.reporting-enabled:false}") boolean enabled) {
    this.data = data;
    this.signals = signals;
    this.access = access;
    this.enabled = enabled;
    this.schema = schema;
  }

  public void contact(JdbcClient db, UUID a, UUID b) {
    if (!schema.blockingReady()) return;
    db.sql("select trust_private.lock_pair(:a,:b)").param("a", a).param("b", b).query().singleRow();
    if (db.sql("select trust_private.blocked(:a,:b)")
        .param("a", a)
        .param("b", b)
        .query(Boolean.class)
        .single())
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "New contact is unavailable between these accounts");
  }

  private UUID subject(JdbcClient db, UUID actor, String kind, UUID target) {
    if (kind.equals("user")) {
      one(
          db.sql(
                  "select :t::uuid as id where exists(select 1 from public.talent_profiles where"
                      + " id=:t and published) or exists(select 1 from public.bookings where :a"
                      + " in(client_id,talent_id) and :t in(client_id,talent_id))")
              .param("t", target)
              .param("a", actor));
      return target;
    }
    String sql =
        switch (kind) {
          case "portfolio" ->
              "select talent_id as subject from public.portfolio_assets where id=:t";
          case "message" -> "select sender_id as subject from public.messages where id=:t";
          case "review" -> "select reviewer_id as subject from public.user_reviews where id=:t";
          default ->
              throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report target");
        };
    return TrustAccess.id(one(db.sql(sql).param("t", target)), "subject");
  }

  public List<Map<String, Object>> blocks(UUID actor) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db ->
            rows(
                db.sql(
                        "select blocked_id,created_at from public.user_blocks where blocker_id=:a"
                            + " order by created_at desc,blocked_id limit 100")
                    .param("a", actor)));
  }

  public Map<String, Object> block(UUID actor, UUID target, boolean blocked) {
    TrustAccess.enabled(enabled);
    if (actor.equals(target))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another account");
    access.rate(actor, "block", 20);
    return data.as(
        actor,
        db -> {
          if (blocked) {
            subject(db, actor, "user", target);
            db.sql(
                    "insert into public.user_blocks(blocker_id,blocked_id) values(:a,:b) on"
                        + " conflict do nothing")
                .param("a", actor)
                .param("b", target)
                .update();
          } else
            db.sql("delete from public.user_blocks where blocker_id=:a and blocked_id=:b")
                .param("a", actor)
                .param("b", target)
                .update();
          return Map.of("blocked_id", target, "blocked", blocked);
        });
  }

  public Map<String, Object> report(
      UUID actor, String kind, UUID target, String category, String description) {
    TrustAccess.enabled(enabled);
    access.rate(actor, "report", 5);
    return data.as(
        actor,
        db -> {
          TrustAccess.lock(db, actor);
          var existing =
              rows(
                  db.sql(
                          "select id,status,created_at from public.safety_reports where"
                              + " reporter_id=:a and target_kind=:k and target_id=:t and"
                              + " category=:c and description=:d")
                      .param("a", actor)
                      .param("k", kind)
                      .param("t", target)
                      .param("c", category)
                      .param("d", description.trim()));
          if (!existing.isEmpty()) return existing.getFirst();
          UUID subject = subject(db, actor, kind, target);
          if (subject.equals(actor))
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "You cannot report your own content");
          var result =
              one(
                  db.sql(
                          "insert into"
                              + " public.safety_reports(reporter_id,subject_id,target_kind,target_id,category,description)"
                              + " values(:a,:s,:k,:t,:c,:d) returning id,status,created_at")
                      .param("a", actor)
                      .param("s", subject)
                      .param("k", kind)
                      .param("t", target)
                      .param("c", category)
                      .param("d", description.trim()));
          TrustAccess.audit(
              db, actor, actor, "safety", "reported", TrustAccess.id(result, "id"), null);
          return result;
        });
  }

  public List<Map<String, Object>> reports(UUID actor, int offset) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db ->
            rows(
                db.sql(
                        "select"
                            + " r.id,r.target_kind,r.target_id,r.category,r.status,r.created_at,a.id"
                            + " as action_id,a.decision,a.reason from public.safety_reports r left"
                            + " join public.moderation_actions a on a.report_id=r.id where"
                            + " r.reporter_id=:u order by r.created_at desc,r.id limit 25 offset"
                            + " :o")
                    .param("u", actor)
                    .param("o", offset)));
  }

  public List<Map<String, Object>> actions(UUID actor) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db ->
            rows(
                db.sql(
                        "select id,decision,reason,policy_version,created_at from"
                            + " public.moderation_actions where subject_id=:u and"
                            + " decision<>'dismissed' order by created_at desc,id limit 100")
                    .param("u", actor)));
  }

  public List<Map<String, Object>> queue(UUID actor, int offset) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db -> {
          TrustAccess.reviewer(db, "moderation");
          return rows(
              db.sql(
                      "select * from public.safety_reports where status='OPEN' order by"
                          + " created_at,id limit 25 offset :o")
                  .param("o", offset));
        });
  }

  public Map<String, Object> decide(UUID actor, UUID report, String decision, String reason) {
    TrustAccess.enabled(enabled);
    access.rate(actor, "moderation", 30);
    return data.as(
        actor,
        db -> {
          TrustAccess.reviewer(db, "moderation");
          TrustAccess.lock(db, report);
          var current =
              one(db.sql("select * from public.safety_reports where id=:id").param("id", report));
          if (actor.equals(TrustAccess.id(current, "reporter_id"))
              || actor.equals(TrustAccess.id(current, "subject_id")))
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Independent moderator required");
          var previous =
              rows(
                  db.sql("select * from public.moderation_actions where report_id=:r")
                      .param("r", report));
          if (!previous.isEmpty()) {
            if (previous.getFirst().get("decision").equals(decision)) return previous.getFirst();
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Case already decided; use the appeal workflow");
          }
          if (decision.equals("review_hidden")) {
            if (!current.get("target_kind").equals("review"))
              throw new ResponseStatusException(
                  HttpStatus.BAD_REQUEST, "This decision requires a review report");
            db.sql("update public.user_reviews set hidden=true where id=:id")
                .param("id", TrustAccess.id(current, "target_id"))
                .update();
          }
          var action =
              one(
                  db.sql(
                          "insert into"
                              + " public.moderation_actions(report_id,subject_id,reviewer_id,decision,reason)"
                              + " values(:r,:s,:u,:d,:reason) returning *")
                      .param("r", report)
                      .param("s", TrustAccess.id(current, "subject_id"))
                      .param("u", actor)
                      .param("d", decision)
                      .param("reason", reason.trim()));
          db.sql("update public.safety_reports set status='RESOLVED' where id=:r")
              .param("r", report)
              .update();
          TrustAccess.audit(
              db,
              actor,
              TrustAccess.id(current, "subject_id"),
              "moderation",
              decision,
              TrustAccess.id(action, "id"),
              reason.trim());
          signals.schedule(TrustAccess.id(action, "subject_id"));
          return action;
        });
  }

  public Map<String, Object> appeal(UUID actor, UUID action, String statement) {
    TrustAccess.enabled(enabled);
    access.rate(actor, "appeal", 5);
    return data.as(
        actor,
        db -> {
          TrustAccess.lock(db, actor);
          var previous =
              rows(
                  db.sql(
                          "select * from public.moderation_appeals where action_id=:a and"
                              + " appellant_id=:u")
                      .param("a", action)
                      .param("u", actor));
          if (!previous.isEmpty()) return previous.getFirst();
          return one(
              db.sql(
                      "insert into public.moderation_appeals(action_id,appellant_id,statement)"
                          + " values(:a,:u,:s) returning *")
                  .param("a", action)
                  .param("u", actor)
                  .param("s", statement.trim()));
        });
  }

  public List<Map<String, Object>> appeals(UUID actor, boolean admin) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db -> {
          if (admin) TrustAccess.reviewer(db, "moderation");
          return rows(
              db.sql(
                      "select * from public.moderation_appeals where "
                          + (admin ? "status in('PENDING','ESCALATED')" : "appellant_id=:u")
                          + " order by created_at,id limit 100")
                  .param("u", actor));
        });
  }

  public Map<String, Object> resolveAppeal(UUID actor, UUID id, String status, String reason) {
    TrustAccess.enabled(enabled);
    access.rate(actor, "appeal_review", 30);
    return data.as(
        actor,
        db -> {
          TrustAccess.reviewer(db, "moderation");
          TrustAccess.lock(db, id);
          var appeal =
              one(db.sql("select * from public.moderation_appeals where id=:id").param("id", id));
          var action =
              one(
                  db.sql("select * from public.moderation_actions where id=:id")
                      .param("id", TrustAccess.id(appeal, "action_id")));
          if (actor.equals(TrustAccess.id(appeal, "appellant_id"))
              || actor.equals(TrustAccess.id(action, "reviewer_id"))
              || actor.equals(TrustAccess.id(action, "subject_id")))
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "A different independent reviewer must decide this appeal");
          if (action.get("report_id") != null
              && db.sql(
                      "select exists(select 1 from public.safety_reports where id=:r and"
                          + " reporter_id=:u)")
                  .param("r", TrustAccess.id(action, "report_id"))
                  .param("u", actor)
                  .query(Boolean.class)
                  .single())
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "The reporting party cannot decide an appeal");
          if (status.equals(appeal.get("status"))) return appeal;
          var result =
              one(
                  db.sql(
                          "update public.moderation_appeals set"
                              + " status=:s,resolution=:r,reviewer_id=:u where id=:id returning *")
                      .param("s", status)
                      .param("r", reason.trim())
                      .param("u", actor)
                      .param("id", id));
          if (status.equals("OVERTURNED") && action.get("decision").equals("review_hidden")) {
            UUID review =
                action.get("review_id") != null
                    ? TrustAccess.id(action, "review_id")
                    : TrustAccess.id(
                        one(
                            db.sql("select target_id from public.safety_reports where id=:id")
                                .param("id", TrustAccess.id(action, "report_id"))),
                        "target_id");
            db.sql("update public.user_reviews set hidden=false where id=:id")
                .param("id", review)
                .update();
          }
          TrustAccess.audit(
              db,
              actor,
              TrustAccess.id(action, "subject_id"),
              "moderation",
              "appeal_" + status,
              id,
              reason.trim());
          signals.schedule(TrustAccess.id(action, "subject_id"));
          return result;
        });
  }
}
