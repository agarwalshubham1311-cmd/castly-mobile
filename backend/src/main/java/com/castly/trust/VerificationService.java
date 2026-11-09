package com.castly.trust;

import static com.castly.api.ActorDatabase.*;

import com.castly.api.ActorDatabase;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VerificationService {
  private final ActorDatabase data;
  private final TrustAccess access;
  private final boolean enabled;
  private final int validDays;

  public VerificationService(
      ActorDatabase data,
      TrustAccess access,
      @Value("${castly.trust.verification-enabled:false}") boolean enabled,
      @Value("${castly.trust.verification-valid-days:365}") int validDays) {
    this.data = data;
    this.access = access;
    this.enabled = enabled;
    this.validDays = validDays;
    if (validDays < 1 || validDays > 730)
      throw new IllegalStateException("Invalid verification validity policy");
  }

  public String badgeField() {
    return enabled
        ? "exists(select 1 from public.creator_verification_badges v where"
            + " v.user_id=talent_profiles.id and v.verified_until>now() and v.portfolio_reviewed"
            + " and v.profile_facts=trust_private.review_facts(talent_profiles)) as"
            + " verified_creator"
        : "false as verified_creator";
  }

  public Map<String, Object> own(UUID actor) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db -> {
          var found =
              rows(
                  db.sql(
                          "select id,user_id,case when status='VERIFIED' and expires_at<=now() then"
                              + " 'EXPIRED' when status='VERIFIED' and not exists(select 1 from public.creator_verification_badges v join public.talent_profiles t on t.id=v.user_id where v.user_id=verification_requests.user_id and v.portfolio_reviewed and v.profile_facts=trust_private.review_facts(t)) then 'REVOKED' else status end as"
                              + " status,statement,revision,submitted_at,expires_at from"
                              + " public.verification_requests where user_id=:u")
                      .param("u", actor));
          return found.isEmpty() ? Map.of("status", "NOT_SUBMITTED") : found.getFirst();
        });
  }

  public List<Map<String, Object>> history(UUID actor) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db ->
            rows(
                db.sql(
                        "select d.id,d.decision,d.reason,d.created_at,d.revision from"
                            + " public.verification_decisions d join public.verification_requests r"
                            + " on r.id=d.request_id where r.user_id=:u order by d.created_at"
                            + " desc,d.id limit 100")
                    .param("u", actor)));
  }

  public Map<String, Object> submit(
      UUID actor, String statement, List<UUID> assets, String consent) {
    TrustAccess.enabled(enabled);
    if (!"profile-review-v1".equals(consent))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Consent to the profile review policy is required");
    access.rate(actor, "verification_submit", 5);
    return data.as(
        actor,
        db -> {
          TrustAccess.lock(db, actor);
          var existing =
              rows(
                  db.sql("select * from public.verification_requests where user_id=:u for update")
                      .param("u", actor));
          Map<String, Object> request;
          if (existing.isEmpty())
            request =
                one(
                    db.sql(
                            "insert into"
                                + " public.verification_requests(user_id,statement,consent_version)"
                                + " values(:u,:s,:c) returning *")
                        .param("u", actor)
                        .param("s", statement.trim())
                        .param("c", consent));
          else {
            var old = existing.getFirst();
            if (Set.of("PENDING", "UNDER_REVIEW").contains(old.get("status"))) return old;
            request =
                one(
                    db.sql(
                            "update public.verification_requests set"
                                + " statement=:s,consent_version=:c,status='PENDING',revision=revision+1,submitted_at=now(),expires_at=null"
                                + " where user_id=:u returning *")
                        .param("u", actor)
                        .param("s", statement.trim())
                        .param("c", consent));
          }
          UUID id = TrustAccess.id(request, "id");
          db.sql(
                  "insert into"
                      + " public.verification_revisions(request_id,revision,owner_id,statement,consent_version)"
                      + " values(:r,:v,:u,:s,:c)")
              .param("r", id)
              .param("v", request.get("revision"))
              .param("u", actor)
              .param("s", statement.trim())
              .param("c", consent)
              .update();
          for (UUID asset : new LinkedHashSet<>(assets)) {
            var item =
                one(
                    db.sql(
                            "select id,path from public.portfolio_assets where id=:id and"
                                + " talent_id=:u")
                        .param("id", asset)
                        .param("u", actor));
            db.sql(
                    "insert into"
                        + " public.verification_evidence(request_id,revision,owner_id,portfolio_asset_id,portfolio_path)"
                        + " values(:r,:v,:u,:a,:p)")
                .param("r", id)
                .param("v", request.get("revision"))
                .param("u", actor)
                .param("a", asset)
                .param("p", item.get("path"))
                .update();
          }
          TrustAccess.audit(
              db,
              actor,
              actor,
              "verification",
              "submitted",
              id,
              "Profile and portfolio review consent: profile-review-v1");
          return request;
        });
  }

  public List<Map<String, Object>> queue(UUID actor, int limit, int offset) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db -> {
          TrustAccess.reviewer(db, "verification");
          return rows(
              db.sql(
                      "select r.*,t.display_name from public.verification_requests r left join"
                          + " public.talent_profiles t on t.id=r.user_id where r.status in"
                          + " ('PENDING','UNDER_REVIEW') order by r.submitted_at,r.id limit :l"
                          + " offset :o")
                  .param("l", limit)
                  .param("o", offset));
        });
  }

  public List<Map<String, Object>> evidence(UUID actor, UUID request) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db ->
            rows(
                db.sql(
                        "select e.id,e.portfolio_asset_id,e.portfolio_path,e.revision from"
                            + " public.verification_evidence e join public.verification_requests r"
                            + " on r.id=e.request_id where r.id=:r and (r.user_id=:u or"
                            + " trust_private.is_reviewer('verification')) order by e.created_at"
                            + " limit 50")
                    .param("r", request)
                    .param("u", actor)));
  }

  public Map<String, Object> decide(UUID actor, UUID id, String decision, String reason) {
    TrustAccess.enabled(enabled);
    access.rate(actor, "verification_review", 30);
    return data.as(
        actor,
        db -> {
          TrustAccess.reviewer(db, "verification");
          var request =
              one(
                  db.sql("select * from public.verification_requests where id=:id for update")
                      .param("id", id));
          UUID owner = TrustAccess.id(request, "user_id");
          if (owner.equals(actor))
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Reviewers cannot review their own application");
          if (decision.equals(request.get("status"))) return request;
          if (decision.equals("VERIFIED")
              && db.sql(
                          "select count(*) from public.verification_evidence where request_id=:id"
                              + " and revision=:v and portfolio_asset_id is not null")
                      .param("id", id)
                      .param("v", request.get("revision"))
                      .query(Long.class)
                      .single()
                  == 0)
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Submitted portfolio evidence is no longer available");
          var result =
              one(
                  db.sql(
                          "update public.verification_requests set status=:s,expires_at=case when"
                              + " :s='VERIFIED' then now()+(:days*interval '1 day') else expires_at"
                              + " end where id=:id returning *")
                      .param("s", decision)
                      .param("days", validDays)
                      .param("id", id));
          db.sql(
                  "insert into"
                      + " public.verification_decisions(request_id,revision,reviewer_id,decision,reason)"
                      + " values(:r,:v,:a,:s,:reason)")
              .param("r", id)
              .param("v", result.get("revision"))
              .param("a", actor)
              .param("s", decision)
              .param("reason", reason.trim())
              .update();
          if (Set.of("VERIFIED", "REVOKED").contains(decision))
            db.sql(
                    "insert into"
                        + " public.creator_verification_badges(user_id,verified_until,portfolio_reviewed,profile_facts)"
                        + " values(:u,case when :s='VERIFIED' then now()+(:days*interval '1 day')"
                        + " else now() end,:ok,(select trust_private.review_facts(t) from"
                        + " public.talent_profiles t where t.id=:u)) on conflict(user_id) do update"
                        + " set verified_until=excluded.verified_until,portfolio_reviewed=excluded.portfolio_reviewed,profile_facts=excluded.profile_facts,updated_at=now()")
                .param("u", owner)
                .param("s", decision)
                .param("days", validDays)
                .param("ok", decision.equals("VERIFIED"))
                .update();
          TrustAccess.audit(db, actor, owner, "verification", decision, id, reason.trim());
          return result;
        });
  }
}
