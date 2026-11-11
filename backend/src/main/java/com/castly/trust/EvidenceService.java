package com.castly.trust;

import static com.castly.api.ActorDatabase.*;

import com.castly.api.ActorDatabase;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EvidenceService {
  private final ActorDatabase data;
  private final TrustSchema schema;
  private final TrustAccess access;
  private final DisputeService disputes;
  private final EvidenceGateway gateway;
  private final boolean enabled;
  private final String policyVersion;
  private final Semaphore processing = new Semaphore(2);

  public EvidenceService(
      ActorDatabase data,
      TrustAccess access,
      DisputeService disputes,
      EvidenceGateway gateway,
      TrustSchema schema,
      @Value("${castly.trust.evidence-enabled:false}") boolean enabled,
      @Value("${castly.trust.evidence-policy-version:}") String policyVersion) {
    this.data = data;
    this.schema = schema;
    this.access = access;
    this.disputes = disputes;
    this.gateway = gateway;
    this.enabled = enabled;
    this.policyVersion = policyVersion;
  }

  public Map<String, Object> configuration(UUID actor) {
    disputes.requireEnabled();
    if (!enabled || !gateway.configured() || policyVersion.isBlank())
      return Map.of("enabled", false);
    return data.as(
        actor,
        db -> {
          var policy =
              rows(
                  db.sql(
                          "select version,retention_days,max_bytes from"
                              + " trust_private.evidence_policies where version=:v and approved and"
                              + " accepting_uploads")
                      .param("v", policyVersion));
          if (policy.isEmpty()) return Map.of("enabled", false);
          var result = new LinkedHashMap<>(policy.getFirst());
          result.put("enabled", true);
          result.put("accepted_types", List.of("image/jpeg", "image/png"));
          result.put("max_pixels", 12_000_000);
          result.put("sanitized_max_dimension", 1600);
          return result;
        });
  }

  public Map<String, Object> upload(
      UUID actor, String token, UUID dispute, String consent, String encoded) {
    disputes.requireEnabled();
    var policy = configuration(actor);
    if (!Boolean.TRUE.equals(policy.get("enabled")) || !policyVersion.equals(consent))
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Image evidence requires an approved active privacy policy and configured private"
              + " storage");
    access.rate(actor, "evidence_upload", 3);
    if (!processing.tryAcquire())
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Image processing is busy. Retry shortly.");
    try {
      byte[] png = EvidenceImage.sanitize(encoded);
      if (png.length > ((Number) policy.get("max_bytes")).intValue())
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Image exceeds the approved evidence size limit");
      String hash;
      try {
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
      } catch (Exception impossible) {
        throw new IllegalStateException(impossible);
      }
      var reserved =
          data.as(
              actor,
              db -> {
                one(
                    db.sql(
                            "select id from public.booking_disputes where id=:d and owner_id=:u for"
                                + " update")
                        .param("d", dispute)
                        .param("u", actor));
                var old =
                    rows(
                        db.sql(
                                "select *,expires_at>statement_timestamp() as usable from"
                                    + " public.dispute_evidence where dispute_id=:d and owner_id=:u"
                                    + " and kind='image' and sha256=:h and state<>'DELETED'")
                            .param("d", dispute)
                            .param("u", actor)
                            .param("h", hash));
                if (!old.isEmpty()) {
                  var existing = old.getFirst();
                  if (existing.get("state").equals("READY")
                      && Boolean.TRUE.equals(existing.get("usable"))) return existing;
                  throw new ResponseStatusException(
                      HttpStatus.CONFLICT,
                      "This image has an incomplete or expired evidence record. Remove it or"
                          + " request retention cleanup before retrying.");
                }
                return one(
                    db.sql(
                            "insert into"
                                + " public.dispute_evidence(dispute_id,owner_id,kind,consent_version,byte_count,sha256)"
                                + " values(:d,:u,'image',:v,:n,:h) returning *")
                        .param("d", dispute)
                        .param("u", actor)
                        .param("v", consent)
                        .param("n", png.length)
                        .param("h", hash));
              });
      UUID id = TrustAccess.id(reserved, "id");
      if (reserved.get("state").equals("READY")) return safe(reserved);
      try {
        gateway.upload(token, reserved.get("object_path").toString(), png);
        return data.as(
            actor,
            db -> {
              db.sql("select set_config('castly.evidence_verified','true',true)")
                  .query()
                  .singleRow();
              var result =
                  one(
                      db.sql(
                              "update public.dispute_evidence set state='READY' where id=:id and"
                                  + " owner_id=:u returning *")
                          .param("id", id)
                          .param("u", actor));
              TrustAccess.audit(db, actor, actor, "disputes", "evidence_uploaded", id, null);
              return safe(result);
            });
      } catch (RuntimeException failure) {
        try {
          data.as(
              actor,
              db ->
                  db.sql(
                          "update public.dispute_evidence set state='FAILED' where id=:id and"
                              + " owner_id=:u and state='RESERVED'")
                      .param("id", id)
                      .param("u", actor)
                      .update());
        } catch (RuntimeException ignored) {
        }
        throw failure;
      }
    } finally {
      processing.release();
    }
  }

  private Map<String, Object> safe(Map<String, Object> row) {
    return Map.of(
        "id",
        row.get("id"),
        "kind",
        row.get("kind"),
        "state",
        row.get("state"),
        "expires_at",
        row.get("expires_at"));
  }

  public Map<String, Object> signed(UUID actor, String token, UUID dispute, UUID id) {
    disputes.requireEnabled();
    var evidence =
        data.as(
            actor,
            db -> {
              one(db.sql("select id from public.booking_disputes where id=:d").param("d", dispute));
              var row =
                  one(
                      db.sql(
                              "select * from public.dispute_evidence where id=:id and dispute_id=:d"
                                  + " and kind='image' and state='READY' and"
                                  + " expires_at>statement_timestamp()")
                          .param("id", id)
                          .param("d", dispute));
              TrustAccess.audit(
                  db,
                  actor,
                  TrustAccess.id(row, "owner_id"),
                  "disputes",
                  "evidence_access_authorized",
                  id,
                  null);
              return row;
            });
    return Map.of(
        "url",
        gateway.sign(
            token,
            evidence.get("object_path").toString(),
            java.time.Instant.parse(evidence.get("expires_at").toString())),
        "expires_in",
        Math.min(
            60,
            Math.max(
                0,
                java.time.Duration.between(
                        java.time.Instant.now(),
                        java.time.Instant.parse(evidence.get("expires_at").toString()))
                    .getSeconds())));
  }

  public void remove(UUID actor, String token, UUID id, boolean retention) {
    TrustAccess.enabled(schema.evidenceReady());
    var row =
        data.as(
            actor,
            db -> {
              if (retention) TrustAccess.reviewer(db, "retention");
              var identity =
                  one(
                      db.sql("select object_path from public.dispute_evidence where id=:id")
                          .param("id", id));
              db.sql("select pg_advisory_xact_lock(hashtextextended(:path,83))")
                  .param("path", identity.get("object_path"))
                  .query()
                  .singleRow();
              var evidence =
                  one(
                      db.sql(
                              "select *,expires_at<=statement_timestamp() as expired from"
                                  + " public.dispute_evidence where id=:id "
                                  + (retention
                                      ? "and kind='image' and expires_at<=statement_timestamp()"
                                      : "and owner_id=:u")
                                  + " for update")
                          .param("id", id)
                          .param("u", actor));
              if (evidence.get("state").equals("DELETED")) return evidence;
              if (Set.of("RESERVED", "FAILED").contains(evidence.get("state"))
                  && java.time.Instant.parse(evidence.get("upload_deadline").toString())
                      .isAfter(java.time.Instant.now()))
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Upload may still be settling. Retry cleanup after the five-minute upload lease"
                        + " ends.");
              if (!retention
                  && Boolean.TRUE.equals(evidence.get("expired"))
                  && evidence.get("kind").equals("image"))
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Expired image deletion requires an authorized retention operator");
              db.sql("select set_config('castly.evidence_delete_authorized','true',true)")
                  .query()
                  .singleRow();
              return one(
                  db.sql(
                          "update public.dispute_evidence set state='DELETING' where id=:id"
                              + " returning *")
                      .param("id", id));
            });
    if (row.get("state").equals("DELETED")) return;
    if (row.get("kind").equals("image")) gateway.remove(token, row.get("object_path").toString());
    data.as(
        actor,
        db -> {
          db.sql("select set_config('castly.evidence_removed','true',true)").query().singleRow();
          db.sql("update public.dispute_evidence set state='DELETED' where id=:id")
              .param("id", id)
              .update();
          TrustAccess.audit(
              db,
              actor,
              TrustAccess.id(row, "owner_id"),
              "disputes",
              retention ? "evidence_retention_deleted" : "evidence_withdrawn",
              id,
              null);
          return null;
        });
  }

  public Map<String, Object> cleanup(UUID actor, String token) {
    TrustAccess.enabled(schema.evidenceReady());
    access.rate(actor, "retention_cleanup", 5);
    var ids =
        data.as(
            actor,
            db -> {
              TrustAccess.reviewer(db, "retention");
              return db.sql(
                      "select id from public.dispute_evidence where kind='image' and"
                          + " state<>'DELETED' and expires_at<=statement_timestamp() order by"
                          + " expires_at,id limit 10")
                  .query(UUID.class)
                  .list();
            });
    int deleted = 0, failed = 0;
    for (UUID id : ids) {
      try {
        remove(actor, token, id, true);
        deleted++;
      } catch (RuntimeException retryRequired) {
        failed++;
      }
    }
    return Map.of("deleted", deleted, "failed", failed, "batch_limit", 10);
  }
}
