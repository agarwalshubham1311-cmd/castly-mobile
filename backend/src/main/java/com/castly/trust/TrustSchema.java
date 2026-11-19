package com.castly.trust;

import com.castly.api.ActorDatabase;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read-only startup capabilities. Restart after migration; disabled deployments require no new
 * tables.
 */
@Component
public class TrustSchema {
  private final boolean blockingReady, evidenceReady;

  public TrustSchema(
      ActorDatabase data,
      @Value("${castly.trust.verification-enabled:false}") boolean verification,
      @Value("${castly.trust.reviews-enabled:false}") boolean reviews,
      @Value("${castly.trust.reporting-enabled:false}") boolean reporting,
      @Value("${castly.trust.signals-enabled:false}") boolean signals,
      @Value("${castly.trust.disputes-enabled:false}") boolean disputes,
      @Value("${castly.trust.evidence-enabled:false}") boolean evidence) {
    var ready =
        data.as(
            null,
            db -> {
              var result = new HashMap<String, Boolean>();
              boolean helpers =
                  functions(db, "trust_private.actor_id()", "trust_private.is_reviewer(text)");
              result.put(
                  "blocking",
                  tables(db, "public.user_blocks")
                      && functions(
                          db,
                          "trust_private.blocked(uuid,uuid)",
                          "trust_private.lock_pair(uuid,uuid)"));
              result.put(
                  "verification",
                  helpers
                      && tables(
                          db,
                          "public.verification_requests",
                          "public.verification_evidence",
                          "public.verification_decisions",
                          "public.creator_verification_badges",
                          "public.verification_revisions")
                      && functions(db, "trust_private.review_facts(public.talent_profiles)"));
              result.put(
                  "reviews",
                  helpers
                      && tables(
                          db,
                          "public.booking_completion_confirmations",
                          "public.user_reviews",
                          "public.trust_signal_snapshots",
                          "public.moderation_actions"));
              result.put(
                  "reporting",
                  helpers
                      && result.get("blocking")
                      && tables(
                          db,
                          "public.safety_reports",
                          "public.moderation_actions",
                          "public.moderation_appeals"));
              result.put(
                  "signals",
                  helpers
                      && tables(
                          db,
                          "public.trust_signal_snapshots",
                          "trust_private.policy_action_counts",
                          "trust_private.risk_snapshots"));
              result.put(
                  "disputes",
                  helpers
                      && tables(
                          db,
                          "public.booking_disputes",
                          "public.dispute_decisions",
                          "public.dispute_evidence",
                          "trust_private.evidence_policies"));
              result.put(
                  "evidence",
                  result.get("disputes")
                      && db.sql(
                              "select count(*)=3 from pg_policies where schemaname='storage' and"
                                  + " tablename='objects' and policyname"
                                  + " in('trust_evidence_upload','trust_evidence_read','trust_evidence_delete')")
                          .query(Boolean.class)
                          .single());
              return result;
            });
    blockingReady = ready.get("blocking");
    evidenceReady = ready.get("evidence");
    var flags =
        Map.of(
            "verification",
            verification,
            "reviews",
            reviews,
            "reporting",
            reporting,
            "signals",
            signals,
            "disputes",
            disputes,
            "evidence",
            evidence);
    flags.forEach(
        (name, on) -> {
          if (on && !ready.get(name))
            throw new IllegalStateException(
                "Required Phase 4 schema or read ACL is missing for " + name);
        });
    if (evidence && !disputes)
      throw new IllegalStateException("New image evidence requires enabled disputes");
  }

  private static boolean tables(JdbcClient db, String... names) {
    return db.sql(
            "select "
                + Arrays.stream(names)
                    .map(
                        name ->
                            "coalesce(has_table_privilege(current_user,to_regclass('"
                                + name
                                + "'),'SELECT'),false)")
                    .reduce((a, b) -> a + " and " + b)
                    .orElse("false"))
        .query(Boolean.class)
        .single();
  }

  private static boolean functions(JdbcClient db, String... names) {
    return db.sql(
            "select "
                + Arrays.stream(names)
                    .map(
                        name ->
                            "coalesce(has_function_privilege(current_user,to_regprocedure('"
                                + name
                                + "'),'EXECUTE'),false)")
                    .reduce((a, b) -> a + " and " + b)
                    .orElse("false"))
        .query(Boolean.class)
        .single();
  }

  public boolean blockingReady() {
    return blockingReady;
  }

  public boolean evidenceReady() {
    return evidenceReady;
  }
}
