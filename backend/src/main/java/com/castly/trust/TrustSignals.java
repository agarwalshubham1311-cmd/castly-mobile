package com.castly.trust;

import static com.castly.api.ActorDatabase.*;

import com.castly.api.ActorDatabase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TrustSignals {
  private final ActorDatabase data;
  private final boolean enabled;
  private final Counter failures, dropped;
  private final TrustRefreshQueue queue;

  public TrustSignals(
      ActorDatabase data,
      MeterRegistry metrics,
      @Value("${castly.trust.signals-enabled:false}") boolean enabled) {
    this.data = data;
    this.enabled = enabled;
    failures = metrics.counter("castly.trust.triage.failures");
    dropped = metrics.counter("castly.trust.triage.deferred");
    queue = new TrustRefreshQueue(this::refresh, failures::increment, dropped::increment);
  }

  public String fields() {
    return enabled
        ? "true as trust_signals_enabled,coalesce(ts.completed_bookings,0) as"
            + " completed_bookings,coalesce(ts.review_count,0) as booking_review_count,case when"
            + " ts.review_count>0 then round(ts.rating_total::numeric/ts.review_count,2) else"
            + " null end as booking_average_rating"
        : "false as trust_signals_enabled,null::bigint as completed_bookings,null::bigint as"
            + " booking_review_count,null::numeric as booking_average_rating";
  }

  public String join() {
    return enabled
        ? " left join public.trust_signal_snapshots ts on ts.user_id=talent_profiles.id "
        : " ";
  }

  public void schedule(UUID subject) {
    if (!enabled || subject == null) return;
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              enqueue(subject);
            }
          });
    } else enqueue(subject);
  }

  private void enqueue(UUID subject) {
    queue.submit(subject);
  }

  void refresh(UUID subject) {
    data.as(
        subject,
        db -> {
          db.sql("select set_config('castly.trust_risk_refresh','true',true)").query().singleRow();
          db.sql(
                  "insert into trust_private.risk_snapshots(user_id) values(:u) on"
                      + " conflict(user_id) do update set updated_at=statement_timestamp()")
              .param("u", subject)
              .update();
          return null;
        });
  }

  public Map<String, Object> own(UUID actor) {
    TrustAccess.enabled(enabled);
    schedule(actor);
    return data.as(
        actor,
        db -> {
          var rows =
              rows(
                  db.sql(
                          "select completed_bookings,review_count,case when review_count>0 then"
                              + " round(rating_total::numeric/review_count,2) else null end as"
                              + " average_rating,policy_version from public.trust_signal_snapshots"
                              + " where user_id=:u")
                      .param("u", actor));
          return rows.isEmpty()
              ? Map.of(
                  "completed_bookings",
                  0,
                  "review_count",
                  0,
                  "policy_version",
                  "trust-indicators-v1")
              : rows.getFirst();
        });
  }

  public Map<String, Object> internal(UUID actor, UUID subject) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db -> {
          TrustAccess.reviewer(db, "moderation");
          schedule(subject);
          var rows =
              rows(
                  db.sql(
                          "select"
                              + " r.confirmed_policy_actions,r.manual_review_recommended,r.policy_version,r.updated_at,(r.confirmed_policy_actions<>coalesce(c.active_actions,0))"
                              + " as stale from trust_private.risk_snapshots r left join"
                              + " trust_private.policy_action_counts c on c.user_id=r.user_id where"
                              + " r.user_id=:u")
                      .param("u", subject));
          if (rows.isEmpty() || Boolean.TRUE.equals(rows.getFirst().get("stale"))) {
            schedule(subject);
            return Map.of("status", "pending_calculation", "policy_version", "manual-review-v1");
          }
          return rows.getFirst();
        });
  }

  @PreDestroy
  public void close() {
    queue.close();
  }
}
