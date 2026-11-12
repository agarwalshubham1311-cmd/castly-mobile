package com.castly.engagement;

import static com.castly.api.ActorDatabase.*;

import com.castly.api.ActorDatabase;
import com.castly.discovery.Phase5Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal worker only; never reachable through a client queue/dispatch endpoint. */
public class EngagementWorker implements AutoCloseable {
  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final DataSource source;
  private final ActorDatabase actors;
  private final Phase5Service discovery;
  private final Phase6Settings flags;
  private final DeliveryProvider provider;
  private final ObjectMapper json;
  private UUID cursor = new UUID(0, 0);

  public EngagementWorker(
      DataSource source,
      ActorDatabase actors,
      Phase5Service discovery,
      Phase6Settings flags,
      DeliveryProvider provider,
      ObjectMapper json) {
    this.source = source;
    this.db = JdbcClient.create(source);
    this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    this.actors = actors;
    this.discovery = discovery;
    this.flags = flags;
    this.provider = provider;
    this.json = json;
    boolean safe =
        db.sql(
                "select current_user='castly_engagement_worker' and not rolsuper and not"
                    + " rolbypassrls and not rolcreatedb and not rolcreaterole and not"
                    + " rolreplication and not exists(select 1 from pg_auth_members where"
                    + " member=pg_roles.oid) and not exists(select 1 from pg_class where"
                    + " relowner=pg_roles.oid and relkind in('r','p')) from pg_roles where"
                    + " rolname=current_user")
            .query(Boolean.class)
            .single();
    if (!safe) throw new IllegalStateException("Dedicated non-owner delivery worker role required");
  }

  public void tick(Instant now) {
    if (!flags.worker) return;
    if (flags.engagement) schedule(now);
    db.sql(
            "update engagement_private.outbox set"
                + " state='uncertain',error_code='dispatch_lease_expired' where state='sending' and"
                + " lease_until<:now")
        .param("now", at(now))
        .update();
    for (int i = 0; i < 10; i++) {
      UUID id =
          tx.execute(
              status -> {
                var jobs =
                    rows(
                        db.sql(
                                "select id,state,channel from engagement_private.outbox where state"
                                    + " in('queued','ticket') and next_at<=:now and (state='queued'"
                                    + " or channel='push') order by next_at,id limit 1 for update"
                                    + " skip locked")
                            .param("now", at(now)));
                if (jobs.isEmpty()) return null;
                var job = jobs.getFirst();
                UUID jobId = UUID.fromString(job.get("id").toString());
                if (job.get("state").equals("queued"))
                  db.sql(
                          "update engagement_private.outbox set state='sending',lease_until=:lease"
                              + " where id=:id")
                      .param("id", jobId)
                      .param("lease", at(now.plusSeconds(60)))
                      .update();
                else
                  db.sql("update engagement_private.outbox set next_at=:next where id=:id")
                      .param("id", jobId)
                      .param("next", at(now.plusSeconds(60)))
                      .update();
                return jobId;
              });
      if (id == null) break;
      dispatch(id, now);
    }
    db.sql("delete from engagement_private.events where day<current_date-90").update();
    db.sql(
            "delete from engagement_private.outbox where created_at<statement_timestamp()-interval"
                + " '30 days'")
        .update();
    db.sql(
            "delete from engagement_private.webhooks where"
                + " created_at<statement_timestamp()-interval '30 days'")
        .update();
    db.sql("delete from engagement_private.schedule_sources where local_day<current_date-30")
        .update();
    db.sql("delete from engagement_private.budgets where day<current_date-30").update();
  }

  private void dispatch(UUID id, Instant now) {
    tx.execute(
        status -> {
          var seed =
              rows(
                  db.sql("select recipient_id from engagement_private.outbox where id=:id")
                      .param("id", id));
          if (seed.isEmpty()) return null;
          UUID recipient = UUID.fromString(seed.getFirst().get("recipient_id").toString());
          var preferences =
              rows(
                  db.sql("select * from public.engagement_preferences where user_id=:u for update")
                      .param("u", recipient));
          var jobs =
              rows(
                  db.sql("select * from engagement_private.outbox where id=:id for update")
                      .param("id", id));
          if (jobs.isEmpty()) return null;
          var job = jobs.getFirst();
          String state = job.get("state").toString(),
              kind = job.get("kind").toString(),
              channel = job.get("channel").toString();
          if (!Set.of("sending", "ticket").contains(state)) return null;
          if (preferences.isEmpty()) {
            finish(id, "suppressed", "no_consent", null, null);
            return null;
          }
          var p = preferences.getFirst();
          boolean optional = NotificationTemplates.optional(kind), push = channel.equals("push");
          boolean consent =
              push
                  ? bool(p, kind.equals("message_received") ? "push_messages" : "push_bookings")
                  : bool(p, optional ? "email_optional" : "email_transactional");
          if (optional)
            consent &=
                flags.engagement
                    && bool(
                        p,
                        switch (kind) {
                          case "booking_reminder" -> "reminders";
                          case "saved_digest" -> "saved_alerts";
                          default -> "reengagement";
                        });
          if (!(push ? flags.push : flags.email)
              || !consent
              || !authorized(job, now, p.get("timezone").toString())
              || Instant.parse(job.get("created_at").toString())
                  .isBefore(now.minus(Duration.ofDays(7)))) {
            finish(id, "suppressed", "source_or_consent_unavailable", null, null);
            return null;
          }
          if (state.equals("ticket")) {
            if (push) receipt(job, now);
            return null;
          }
          Instant allowed =
              QuietHours.nextAllowed(
                  now,
                  p.get("timezone").toString(),
                  ((Number) p.get("quiet_start")).intValue(),
                  ((Number) p.get("quiet_end")).intValue());
          if (allowed.isAfter(now)) {
            defer(id, allowed, "quiet_hours");
            return null;
          }
          var zone = ZoneId.of(p.get("timezone").toString());
          var dayStart = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
          int count =
              db.sql(
                      "select count(*)::int from engagement_private.outbox where recipient_id=:u"
                          + " and id<>:id and state in('sending','ticket','delivered','uncertain')"
                          + " and dispatched_at>=:start and (cast(:optional as boolean) and kind"
                          + " in('booking_reminder','saved_digest','reengagement') or not"
                          + " cast(:optional as boolean) and channel=:channel)")
                  .param("u", recipient)
                  .param("id", id)
                  .param("start", at(dayStart))
                  .param("optional", optional)
                  .param("channel", channel)
                  .query(Integer.class)
                  .single();
          if (count >= (optional ? 3 : push ? 30 : 10)) {
            defer(
                id,
                now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant(),
                "frequency_cap");
            return null;
          }
          if ((push ? flags.pushProvider : flags.emailProvider).equals("disabled")) {
            defer(id, now.plusSeconds(3600), "provider_disabled");
            return null;
          }
          String destination;
          if (push) {
            var devices =
                rows(
                    db.sql(
                            "select token from public.push_devices where id=:d and user_id=:u and"
                                + " active and expires_at>:now for update")
                        .param("d", uuid(job, "device_id"))
                        .param("u", recipient)
                        .param("now", at(now)));
            if (devices.isEmpty()) {
              finish(id, "suppressed", "device_unavailable", null, null);
              return null;
            }
            destination = devices.getFirst().get("token").toString();
          } else {
            var contacts =
                rows(
                    db.sql(
                            "select email from engagement_private.contacts c where user_id=:u and"
                                + " verified_at>:now-interval '1 day' and not exists(select 1 from"
                                + " engagement_private.suppressions s where"
                                + " s.email_hash=encode(sha256(convert_to(lower(c.email),'UTF8')),'hex'))")
                        .param("u", recipient)
                        .param("now", at(now)));
            if (contacts.isEmpty()) {
              finish(id, "suppressed", "verified_contact_unavailable", null, null);
              return null;
            }
            destination = contacts.getFirst().get("email").toString();
          }
          var budget =
              rows(
                  db.sql(
                          "insert into engagement_private.budgets(day,channel,dispatches)"
                              + " values(cast(:day as date),:channel,1) on conflict(day,channel) do"
                              + " update set dispatches=budgets.dispatches+1 where"
                              + " budgets.dispatches<:cap returning dispatches")
                      .param("day", now.atZone(ZoneOffset.UTC).toLocalDate())
                      .param("channel", channel)
                      .param("cap", push ? 1000 : 100));
          if (budget.isEmpty()) {
            defer(
                id,
                now.atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant(),
                "global_budget");
            return null;
          }
          int attempts = ((Number) job.get("attempts")).intValue() + 1;
          db.sql("update engagement_private.outbox set attempts=:n,dispatched_at=:now where id=:id")
              .param("n", attempts)
              .param("now", at(now))
              .param("id", id)
              .update();
          String unsubscribe =
              optional && !push
                  ? flags.apiOrigin
                      + "/api/v1/engagement/unsubscribe/"
                      + new UnsubscribeTokens(flags.unsubscribeKey).issue(id, now)
                  : null;
          var envelope =
              new DeliveryProvider.Envelope(
                  id,
                  channel,
                  destination,
                  kind,
                  flags.appOrigin + "/engagement/open/" + id,
                  unsubscribe);
          DeliveryProvider.Result result;
          try {
            result = provider.send(envelope);
          } catch (Exception e) {
            result = DeliveryProvider.Result.of(DeliveryProvider.Status.UNCERTAIN);
          }
          apply(job, result, now, attempts);
          return null;
        });
  }

  private void receipt(Map<String, Object> job, Instant now) {
    var result = provider.receipt(job.get("receipt").toString());
    if (result.status() == DeliveryProvider.Status.PENDING
        || result.status() == DeliveryProvider.Status.DISABLED) {
      if (Instant.parse(job.get("created_at").toString()).isBefore(now.minus(Duration.ofDays(1))))
        finish(uuid(job, "id"), "uncertain", "receipt_expired", null, null);
      return;
    }
    apply(job, result, now, ((Number) job.get("attempts")).intValue());
  }

  private void apply(
      Map<String, Object> job, DeliveryProvider.Result r, Instant now, int attempts) {
    UUID id = uuid(job, "id");
    switch (r.status()) {
      case ACCEPTED -> {
        if (r.receipt() == null || !r.receipt().matches("[A-Za-z0-9-]{1,200}")) {
          finish(id, "uncertain", "malformed_receipt", null, null);
          break;
        }
        finish(id, "ticket", null, r.receipt(), null);
        db.sql("update engagement_private.outbox set next_at=:next where id=:id")
            .param("id", id)
            .param("next", at(now.plusSeconds(60)))
            .update();
      }
      case DELIVERED -> {
        finish(id, "delivered", null, null, now);
        record(job, "notification_delivered", now);
      }
      case INVALID -> {
        if (job.get("device_id") != null)
          db.sql("update public.push_devices set active=false where id=:d")
              .param("d", uuid(job, "device_id"))
              .update();
        finish(id, "dead", "invalid_device", null, null);
      }
      case RETRY -> {
        if (attempts >= 10) finish(id, "dead", "attempts_exhausted", null, null);
        else defer(id, QuietHours.retry(now, attempts), "provider_rejected_retryable");
      }
      case DISABLED -> defer(id, now.plusSeconds(3600), "provider_disabled");
      case PERMANENT -> finish(id, "dead", "provider_rejected", null, null);
      case UNCERTAIN -> finish(id, "uncertain", "ambiguous_dispatch", null, null);
      default -> finish(id, "uncertain", "unexpected_provider_state", null, null);
    }
  }

  private boolean authorized(Map<String, Object> job, Instant now, String zone) {
    String kind = job.get("kind").toString();
    UUID recipient = uuid(job, "recipient_id");
    if (kind.equals("reengagement"))
      return db.sql(
              "select exists(select 1 from public.engagement_preferences where user_id=:u and"
                  + " last_active_at<statement_timestamp()-interval '14 days')")
          .param("u", recipient)
          .query(Boolean.class)
          .single();
    if (job.get("booking_id") != null
        && !lockSourcePair("public.bookings", uuid(job, "booking_id"))) return false;
    if (job.get("conversation_id") != null
        && !lockSourcePair("public.conversations", uuid(job, "conversation_id"))) return false;
    if (job.get("booking_id") != null)
      return db.sql(
              "select exists(select 1 from public.bookings b where b.id=:b and :u"
                  + " in(b.client_id,b.talent_id) and not"
                  + " trust_private.blocked(b.client_id,b.talent_id) and (:kind='booking_reminder'"
                  + " and b.status in('requested','accepted') and b.proposed_date between :today"
                  + " and :tomorrow or :kind='booking_requested' and b.status='requested' or"
                  + " :kind='booking_accepted' and b.status='accepted' or :kind='booking_declined'"
                  + " and b.status='declined' or :kind='booking_cancelled' and"
                  + " b.status='cancelled'))")
          .param("b", uuid(job, "booking_id"))
          .param("u", recipient)
          .param("kind", kind)
          .param("today", now.atZone(ZoneId.of(zone)).toLocalDate())
          .param("tomorrow", now.atZone(ZoneId.of(zone)).toLocalDate().plusDays(1))
          .query(Boolean.class)
          .single();
    if (job.get("conversation_id") != null)
      return db.sql(
              "select exists(select 1 from public.conversations c where c.id=:c and :u"
                  + " in(c.client_id,c.talent_id) and not"
                  + " trust_private.blocked(c.client_id,c.talent_id) and exists(select 1 from"
                  + " public.bookings b where b.client_id=c.client_id and b.talent_id=c.talent_id"
                  + " and b.status in('accepted','completed')))")
          .param("c", uuid(job, "conversation_id"))
          .param("u", recipient)
          .query(Boolean.class)
          .single();
    if (kind.equals("saved_digest"))
      try {
        return actors.as(
            recipient,
            api -> {
              var s =
                  one(
                      api.sql(
                              "select filters from public.saved_searches where id=:s and alerts and"
                                  + " user_id=:u")
                          .param("s", uuid(job, "search_id"))
                          .param("u", recipient));
              var f = filters(s);
              return discovery.hasDigestMatch(
                  recipient, f.search(now.atZone(ZoneId.of(zone)).toLocalDate()));
            });
      } catch (Exception e) {
        return false;
      }
    return kind.equals("account_activity");
  }

  private boolean lockSourcePair(String table, UUID id) {
    var pairs =
        rows(db.sql("select client_id,talent_id from " + table + " where id=:id").param("id", id));
    if (pairs.isEmpty()) return false;
    var p = pairs.getFirst();
    db.sql("select trust_private.lock_pair(:a,:b)")
        .param("a", uuid(p, "client_id"))
        .param("b", uuid(p, "talent_id"))
        .query()
        .singleRow();
    return true;
  }

  private void record(Map<String, Object> job, String event, Instant now) {
    if (!flags.analytics) return;
    UUID u = uuid(job, "recipient_id");
    var preferences =
        rows(db.sql("select * from public.engagement_preferences where user_id=:u").param("u", u));
    if (preferences.isEmpty()) return;
    var p = preferences.getFirst();
    if (!bool(p, "analytics")) return;
    if (NotificationTemplates.optional(job.get("kind").toString())) {
      String key =
          switch (job.get("kind").toString()) {
            case "booking_reminder" -> "reminders";
            case "saved_digest" -> "saved_alerts";
            default -> "reengagement";
          };
      if (!bool(p, key)
          || !bool(p, job.get("channel").equals("email") ? "email_optional" : "push_bookings"))
        return;
    }
    db.sql(
            "insert into engagement_private.events(subject_key,day,event) values(:s,:d,:e) on"
                + " conflict do nothing")
        .param("s", flags.subject(u))
        .param("d", now.atZone(ZoneOffset.UTC).toLocalDate())
        .param("e", event)
        .update();
  }

  private void finish(UUID id, String state, String error, String receipt, Instant delivered) {
    db.sql(
            "update engagement_private.outbox set"
                + " state=:s,error_code=:e,receipt=coalesce(:r,receipt),delivered_at=coalesce(:d,delivered_at),lease_until=null"
                + " where id=:id")
        .param("id", id)
        .param("s", state)
        .param("e", error, java.sql.Types.VARCHAR)
        .param("r", receipt, java.sql.Types.VARCHAR)
        .param("d", at(delivered), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .update();
  }

  private void defer(UUID id, Instant next, String reason) {
    db.sql(
            "update engagement_private.outbox set"
                + " state='queued',next_at=:n,error_code=:r,lease_until=null where id=:id")
        .param("id", id)
        .param("n", at(next))
        .param("r", reason)
        .update();
  }

  public void schedule(Instant now) {
    if (!flags.engagement) return;
    var users =
        rows(
            db.sql(
                    "select user_id,timezone,last_active_at,reminders,saved_alerts,reengagement"
                        + " from public.engagement_preferences where user_id>:cursor and (reminders"
                        + " or saved_alerts or reengagement) order by user_id limit 50")
                .param("cursor", cursor));
    if (users.isEmpty()) {
      cursor = new UUID(0, 0);
      return;
    }
    for (var p : users) {
      UUID u = uuid(p, "user_id");
      cursor = u;
      var scan =
          rows(
              db.sql(
                      "insert into engagement_private.scan_state(user_id,next_at) values(:u,:next)"
                          + " on conflict(user_id) do update set next_at=excluded.next_at where"
                          + " scan_state.next_at<=:now returning user_id")
                  .param("u", u)
                  .param("next", at(now.plusSeconds(3600)))
                  .param("now", at(now)));
      if (scan.isEmpty()) continue;
      LocalDate day = now.atZone(ZoneId.of(p.get("timezone").toString())).toLocalDate();
      try {
        actors.as(
            u,
            api -> {
              if (bool(p, "reminders")) {
                for (var b :
                    rows(
                        api.sql(
                                "select id from public.bookings where :u in(client_id,talent_id)"
                                    + " and status in('requested','accepted') and proposed_date"
                                    + " between :d and :end order by id limit 20")
                            .param("u", u)
                            .param("d", day)
                            .param("end", day.plusDays(1))))
                  source(api, u, "booking_reminder", uuid(b, "id"), day);
              }
              if (bool(p, "saved_alerts")
                  && Boolean.TRUE.equals(discovery.configuration().get("advanced_search"))) {
                for (var s :
                    rows(
                        api.sql(
                                "select id,filters from public.saved_searches where user_id=:u and"
                                    + " alerts order by id limit 20")
                            .param("u", u))) {
                  if (discovery.hasDigestMatch(u, filters(s).search(day)))
                    source(api, u, "saved_digest", uuid(s, "id"), day);
                }
              }
              if (bool(p, "reengagement")
                  && Instant.parse(p.get("last_active_at").toString())
                      .isBefore(now.minus(Duration.ofDays(14))))
                source(api, u, "reengagement", u, day);
              return null;
            });
      } catch (org.springframework.web.server.ResponseStatusException
          | org.springframework.dao.DataAccessException ignored) {
        /* fail closed; only aggregate scheduler error metrics belong here */
      }
    }
  }

  private EngagementService.Filters filters(Map<String, Object> row) {
    try {
      return json.readValue(row.get("filters").toString(), EngagementService.Filters.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid stored search");
    }
  }

  private void source(JdbcClient api, UUID user, String kind, UUID id, LocalDate day) {
    String key =
        kind
            + ":"
            + id
            + ":"
            + (kind.equals("reengagement") ? day.with(java.time.DayOfWeek.MONDAY) : day);
    api.sql(
            "insert into"
                + " engagement_private.schedule_sources(user_id,kind,source_id,local_day,event_key,booking_id,search_id)"
                + " values(:u,:k,:s,:d,:e,:b,:search) on conflict do nothing")
        .param("u", user)
        .param("k", kind)
        .param("s", id)
        .param("d", day)
        .param("e", key)
        .param("b", kind.equals("booking_reminder") ? id : null, java.sql.Types.OTHER)
        .param("search", kind.equals("saved_digest") ? id : null, java.sql.Types.OTHER)
        .update();
  }

  public void unsubscribe(String token, Instant now) {
    var id = new UnsubscribeTokens(flags.unsubscribeKey).verify(token, now);
    if (id.isEmpty()) return;
    db.sql(
            "update public.engagement_preferences set"
                + " email_optional=false,reminders=false,saved_alerts=false,reengagement=false"
                + " where user_id in(select recipient_id from engagement_private.outbox where"
                + " id=:id and channel='email' and kind"
                + " in('booking_reminder','saved_digest','reengagement'))")
        .param("id", id.get())
        .update();
  }

  public boolean webhook(
      String eventId, String timestamp, String signatures, byte[] body, Instant now) {
    if (!EmailWebhook.verify(flags.webhookSecret, eventId, timestamp, signatures, body, now))
      return false;
    try {
      var event = json.readTree(body);
      String type = event.path("type").asText(),
          receipt = event.path("data").path("email_id").asText();
      if (!Set.of("email.delivered", "email.bounced", "email.complained").contains(type)
          || !receipt.matches("[A-Za-z0-9-]{1,200}")) return false;
      return tx.execute(
          status -> {
            var rows =
                rows(
                    db.sql(
                            "insert into engagement_private.webhooks(event_id) values(:e) on"
                                + " conflict do nothing returning event_id")
                        .param("e", eventId));
            if (rows.isEmpty()) return true;
            var jobs =
                rows(
                    db.sql(
                            "select * from engagement_private.outbox where channel='email' and"
                                + " receipt=:r for update")
                        .param("r", receipt));
            for (var job : jobs) {
              if (type.equals("email.delivered")) {
                if (job.get("state").equals("ticket")) {
                  finish(uuid(job, "id"), "delivered", null, null, now);
                  record(job, "notification_delivered", now);
                }
              } else {
                db.sql(
                        "insert into engagement_private.suppressions(email_hash,reason) select"
                            + " encode(sha256(convert_to(lower(email),'UTF8')),'hex'),:reason from"
                            + " engagement_private.contacts where user_id=:u on conflict do"
                            + " nothing")
                    .param("u", uuid(job, "recipient_id"))
                    .param("reason", type.equals("email.bounced") ? "bounce" : "complaint")
                    .update();
                finish(uuid(job, "id"), "dead", "address_suppressed", null, null);
              }
            }
            return true;
          });
    } catch (Exception e) {
      return false;
    }
  }

  static java.time.OffsetDateTime at(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  static boolean bool(Map<String, Object> row, String key) {
    return Boolean.TRUE.equals(row.get(key));
  }

  static UUID uuid(Map<String, Object> row, String key) {
    return UUID.fromString(row.get(key).toString());
  }

  public void close() {
    if (source instanceof com.zaxxer.hikari.HikariDataSource h) h.close();
  }
}
