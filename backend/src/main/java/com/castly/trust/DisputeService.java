package com.castly.trust;

import static com.castly.api.ActorDatabase.*;

import com.castly.api.ActorDatabase;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DisputeService {
  private final ActorDatabase data;
  private final TrustAccess access;
  private final boolean enabled;

  public DisputeService(
      ActorDatabase data,
      TrustAccess access,
      @Value("${castly.trust.disputes-enabled:false}") boolean enabled) {
    this.data = data;
    this.access = access;
    this.enabled = enabled;
  }

  public void requireEnabled() {
    TrustAccess.enabled(enabled);
  }

  public Map<String, Object> create(UUID actor, UUID booking, String statement, String consent) {
    requireEnabled();
    if (!consent.equals("dispute-text-v1"))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Consent to the dispute policy is required");
    access.rate(actor, "dispute_create", 3);
    return data.as(
        actor,
        db -> {
          var original =
              one(
                  db.sql("select * from public.bookings where id=:b for update")
                      .param("b", booking));
          var existing =
              rows(
                  db.sql(
                          "select * from public.booking_disputes where booking_id=:b and"
                              + " owner_id=:u")
                      .param("b", booking)
                      .param("u", actor));
          if (!existing.isEmpty()) {
            if (existing.getFirst().get("statement").equals(statement.trim()))
              return existing.getFirst();
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "You already have a dispute for this booking. Add a note to the existing case.");
          }
          UUID other =
              TrustAccess.id(
                  original,
                  actor.toString().equals(original.get("client_id").toString())
                      ? "talent_id"
                      : "client_id");
          var result =
              one(
                  db.sql(
                          "insert into"
                              + " public.booking_disputes(booking_id,owner_id,counterparty_id,statement,consent_version)"
                              + " values(:b,:u,:o,:s,:c) returning *")
                      .param("b", booking)
                      .param("u", actor)
                      .param("o", other)
                      .param("s", statement.trim())
                      .param("c", consent));
          TrustAccess.audit(
              db, actor, actor, "disputes", "submitted", TrustAccess.id(result, "id"), null);
          return result;
        });
  }

  public List<Map<String, Object>> list(UUID actor, boolean admin, int offset) {
    requireEnabled();
    return data.as(
        actor,
        db -> {
          if (admin) TrustAccess.reviewer(db, "disputes");
          return rows(
              db.sql(
                      "select id,booking_id,status,created_at,owner_id from public.booking_disputes"
                          + " where "
                          + (admin
                              ? "status not in('RESOLVED','FINAL_RESOLVED') and owner_id<>:u and"
                                  + " counterparty_id<>:u"
                              : "owner_id=:u")
                          + " order by created_at desc,id limit 25 offset :o")
                  .param("u", actor)
                  .param("o", offset));
        });
  }

  public Map<String, Object> details(UUID actor, UUID id) {
    requireEnabled();
    return data.as(
        actor,
        db -> {
          var dispute =
              one(db.sql("select * from public.booking_disputes where id=:id").param("id", id));
          return Map.of(
              "dispute",
              dispute,
              "decisions",
              rows(
                  db.sql(
                          "select id,status,resolution,created_at from public.dispute_decisions"
                              + " where dispute_id=:id order by created_at,id limit 100")
                      .param("id", id)),
              "evidence",
              rows(
                  db.sql(
                          "select id,kind,case when expires_at<=statement_timestamp() and"
                              + " state<>'DELETED' then 'EXPIRED' else state end as state,case when"
                              + " state='READY' and expires_at>statement_timestamp() then note else"
                              + " null end as note,byte_count,created_at,expires_at,consent_version"
                              + " from public.dispute_evidence where dispute_id=:id order by"
                              + " created_at,id limit 100")
                      .param("id", id)));
        });
  }

  public Map<String, Object> note(UUID actor, UUID id, String note, String consent) {
    requireEnabled();
    access.rate(actor, "dispute_note", 10);
    return data.as(
        actor,
        db -> {
          one(
              db.sql(
                      "select id from public.booking_disputes where id=:id and owner_id=:u for"
                          + " update")
                  .param("id", id)
                  .param("u", actor));
          var existing =
              rows(
                  db.sql(
                          "select id,kind,state from public.dispute_evidence where dispute_id=:id"
                              + " and owner_id=:u and kind='note' and note=:n and state='READY'")
                      .param("id", id)
                      .param("u", actor)
                      .param("n", note.trim()));
          if (!existing.isEmpty()) return existing.getFirst();
          var result =
              one(
                  db.sql(
                          "insert into"
                              + " public.dispute_evidence(dispute_id,owner_id,kind,note,consent_version)"
                              + " values(:id,:u,'note',:n,:c) returning id,kind,state")
                      .param("id", id)
                      .param("u", actor)
                      .param("n", note.trim())
                      .param("c", consent));
          TrustAccess.audit(db, actor, actor, "disputes", "note_added", id, null);
          return result;
        });
  }

  public Map<String, Object> appeal(UUID actor, UUID id, String statement) {
    requireEnabled();
    access.rate(actor, "dispute_appeal", 5);
    return data.as(
        actor,
        db -> {
          var current =
              one(
                  db.sql(
                          "select * from public.booking_disputes where id=:id and owner_id=:u for"
                              + " update")
                      .param("id", id)
                      .param("u", actor));
          if ("APPEALED".equals(current.get("status"))
              && statement.trim().equals(current.get("appeal_statement"))) return current;
          var result =
              one(
                  db.sql(
                          "update public.booking_disputes set status='APPEALED',appeal_statement=:s"
                              + " where id=:id and owner_id=:u returning *")
                      .param("s", statement.trim())
                      .param("id", id)
                      .param("u", actor));
          TrustAccess.audit(db, actor, actor, "disputes", "appealed", id, null);
          return result;
        });
  }

  public Map<String, Object> decide(UUID actor, UUID id, String status, String reason) {
    requireEnabled();
    access.rate(actor, "dispute_review", 30);
    return data.as(
        actor,
        db -> {
          TrustAccess.reviewer(db, "disputes");
          TrustAccess.lock(db, id);
          var current =
              one(db.sql("select * from public.booking_disputes where id=:id").param("id", id));
          if (actor.equals(TrustAccess.id(current, "owner_id"))
              || actor.equals(TrustAccess.id(current, "counterparty_id")))
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Independent dispute reviewer required");
          if (Set.of("APPEALED", "ESCALATED").contains(current.get("status"))
              && actor.toString().equals(current.get("first_resolution_by")))
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "A different reviewer must decide an appeal");
          if (status.equals(current.get("status"))
              && reason.trim().equals(current.get("resolution"))) return current;
          var result =
              one(
                  db.sql(
                          "update public.booking_disputes set"
                              + " status=:s,resolution=:r,reviewer_id=:u where id=:id returning *")
                      .param("s", status)
                      .param("r", reason.trim())
                      .param("u", actor)
                      .param("id", id));
          db.sql(
                  "insert into public.dispute_decisions(dispute_id,reviewer_id,status,resolution)"
                      + " values(:id,:u,:s,:r)")
              .param("id", id)
              .param("u", actor)
              .param("s", status)
              .param("r", reason.trim())
              .update();
          TrustAccess.audit(
              db,
              actor,
              TrustAccess.id(current, "owner_id"),
              "disputes",
              status,
              id,
              reason.trim());
          return result;
        });
  }
}
