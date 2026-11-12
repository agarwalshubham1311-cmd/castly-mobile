package com.castly.engagement;

import static com.castly.api.ActorDatabase.*;

import com.castly.api.ActorDatabase;
import com.castly.discovery.*;
import com.castly.trust.TrustAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EngagementService {
  private final ActorDatabase data;
  @org.springframework.beans.factory.annotation.Autowired private Phase5Service discovery;
  private final Phase6Settings flags;
  private final ObjectMapper json;
  private final VerifiedEmailGateway email;
  private final TrustAccess limits;

  public EngagementService(
      ActorDatabase data,
      Phase6Settings flags,
      ObjectMapper json,
      VerifiedEmailGateway email,
      TrustAccess limits) {
    this.data = data;
    this.flags = flags;
    this.json = json;
    this.email = email;
    this.limits = limits;
  }

  void enabled(boolean flag) {
    if (!flag)
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "This engagement feature is not enabled");
  }

  public record Preferences(
      @NotBlank @Size(max = 80) String timezone,
      @Min(0) @Max(23) int quietStart,
      @Min(0) @Max(23) int quietEnd,
      boolean pushBookings,
      boolean pushMessages,
      boolean emailTransactional,
      boolean emailOptional,
      boolean reminders,
      boolean savedAlerts,
      boolean reengagement,
      boolean analytics) {}

  public Map<String, Object> preferences(UUID actor) {
    enabled(flags.any());
    return data.as(
        actor,
        db -> {
          var stored =
              rows(
                  db.sql("select * from public.engagement_preferences where user_id=:u")
                      .param("u", actor));
          if (!stored.isEmpty()) return stored.getFirst();
          var defaults = new LinkedHashMap<String, Object>();
          defaults.put("timezone", "UTC");
          defaults.put("quiet_start", 22);
          defaults.put("quiet_end", 8);
          for (String key :
              List.of(
                  "push_bookings",
                  "push_messages",
                  "email_transactional",
                  "email_optional",
                  "reminders",
                  "saved_alerts",
                  "reengagement",
                  "analytics")) defaults.put(key, false);
          return defaults;
        });
  }

  public Map<String, Object> preferences(UUID actor, Preferences p) {
    enabled(flags.any());
    limits.rate(actor, "phase6_preferences", 20);
    try {
      ZoneId.of(p.timezone());
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an IANA timezone");
    }
    return data.as(
        actor,
        db -> {
          db.sql(
                  "insert into public.engagement_preferences(user_id) values(:u) on conflict do"
                      + " nothing")
              .param("u", actor)
              .update();
          var result =
              one(
                  db.sql(
                          "update public.engagement_preferences set"
                              + " timezone=:tz,quiet_start=:qs,quiet_end=:qe,push_bookings=:pb,push_messages=:pm,email_transactional=:et,email_optional=:eo,reminders=:r,saved_alerts=:s,reengagement=:re,analytics=:a"
                              + " where user_id=:u returning *")
                      .param("u", actor)
                      .param("tz", p.timezone())
                      .param("qs", p.quietStart())
                      .param("qe", p.quietEnd())
                      .param("pb", p.pushBookings())
                      .param("pm", p.pushMessages())
                      .param("et", p.emailTransactional())
                      .param("eo", p.emailOptional())
                      .param("r", p.reminders())
                      .param("s", p.savedAlerts())
                      .param("re", p.reengagement())
                      .param("a", p.analytics()));
          if (!p.analytics())
            db.sql("delete from engagement_private.events where subject_key=:key")
                .param("key", flags.subject(actor))
                .update();
          return result;
        });
  }

  public Map<String, Object> register(UUID actor, UUID deviceKey, String token, String platform) {
    enabled(flags.push);
    limits.rate(actor, "phase6_device", 20);
    if (!Set.of("android", "ios").contains(platform)
        || !token.matches("^(ExponentPushToken|ExpoPushToken)\\[[A-Za-z0-9_-]{10,200}\\]$"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid device registration");
    try {
      return data.as(
          actor,
          db -> {
            var choices =
                rows(
                    db.sql(
                            "select push_bookings,push_messages from public.engagement_preferences"
                                + " where user_id=:u for update")
                        .param("u", actor));
            if (choices.isEmpty()
                || !Boolean.TRUE.equals(choices.getFirst().get("push_bookings"))
                    && !Boolean.TRUE.equals(choices.getFirst().get("push_messages")))
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT,
                  "Choose and save a push preference before registering this device");
            db.sql("select set_config('castly.phase6.device_token',:token,true)")
                .param("token", token)
                .query()
                .singleRow();
            return one(
                db.sql(
                        "insert into public.push_devices(user_id,device_key,token,platform)"
                            + " values(:u,:d,:t,:p) on conflict(user_id,device_key) do update set"
                            + " token=excluded.token,platform=excluded.platform,active=true"
                            + " returning id,device_key,platform,active,expires_at")
                    .param("u", actor)
                    .param("d", deviceKey)
                    .param("t", token)
                    .param("p", platform));
          });
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Device is registered to another session; remove the prior registration first");
    }
  }

  public void unregister(UUID actor, UUID key) {
    enabled(flags.any());
    data.as(
        actor,
        db -> {
          db.sql("delete from public.push_devices where user_id=:u and device_key=:d")
              .param("u", actor)
              .param("d", key)
              .update();
          return null;
        });
  }

  public Map<String, Object> contact(UUID actor, String token) {
    enabled(flags.email);
    limits.rate(actor, "phase6_contact", 5);
    String address =
        email
            .verified(token, actor)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A verified Supabase email address is required; retry after verification"));
    return data.as(
        actor,
        db -> {
          db.sql("select set_config('castly.phase6.contact_verified','yes',true)")
              .query()
              .singleRow();
          db.sql(
                  "insert into engagement_private.contacts(user_id,email,verified_at)"
                      + " values(:u,:e,statement_timestamp()) on conflict(user_id) do update set"
                      + " email=excluded.email,verified_at=excluded.verified_at,updated_at=statement_timestamp()")
              .param("u", actor)
              .param("e", address)
              .update();
          return Map.of("verified", true);
        });
  }

  public record Filters(
      String category,
      String city,
      String region,
      List<String> skills,
      Integer maxRate,
      Integer daysAhead,
      Integer windowDays,
      boolean includeUnknown) {
    public Filters {
      skills = skills == null ? List.of() : List.copyOf(skills);
      if (skills.size() > 10
          || !Set.of(
                  "video",
                  "acting",
                  "photography",
                  "fashion",
                  "music",
                  "editing",
                  "dance",
                  "makeup")
              .containsAll(skills)
          || daysAhead != null && (daysAhead < 0 || daysAhead > 30)
          || windowDays != null && (windowDays < 1 || windowDays > 7))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid saved-search filters");
      new Phase5Service.Search(
          category, city, region, skills, 0, maxRate, null, null, "any", "name", 25, 0);
    }

    public Phase5Service.Search search(LocalDate today) {
      var from = daysAhead == null ? null : today.plusDays(daysAhead);
      return new Phase5Service.Search(
          category,
          city,
          region,
          skills,
          0,
          maxRate,
          from,
          from == null ? null : from.plusDays((windowDays == null ? 1 : windowDays) - 1),
          from != null && !includeUnknown ? "confirmed" : "any",
          "name",
          25,
          0);
    }
  }

  public List<Map<String, Object>> saved(UUID actor) {
    enabled(flags.engagement);
    return data.as(
        actor,
        db ->
            rows(
                db.sql(
                        "select id,label,filters,alerts,created_at from public.saved_searches where"
                            + " user_id=:u order by created_at,id limit 20")
                    .param("u", actor)));
  }

  public Map<String, Object> save(
      UUID actor, UUID id, String label, Filters filters, boolean alerts) {
    enabled(flags.engagement);
    limits.rate(actor, "phase6_saved", 20);
    try {
      String body = json.writeValueAsString(filters);
      return data.as(
          actor,
          db ->
              one(
                  (id == null
                          ? db.sql(
                              "insert into public.saved_searches(user_id,label,filters,alerts)"
                                  + " values(:u,:l,cast(:f as jsonb),:a) returning"
                                  + " id,label,filters,alerts")
                          : db.sql(
                                  "update public.saved_searches set label=:l,filters=cast(:f as"
                                      + " jsonb),alerts=:a where id=:id and user_id=:u returning"
                                      + " id,label,filters,alerts")
                              .param("id", id))
                      .param("u", actor)
                      .param("l", label.trim())
                      .param("f", body)
                      .param("a", alerts)));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filters");
    }
  }

  public List<Map<String, Object>> results(UUID actor, UUID id) {
    enabled(flags.engagement);
    limits.rate(actor, "phase6_results", 20);
    return data.as(
        actor,
        db -> {
          var row =
              one(
                  db.sql("select filters from public.saved_searches where id=:id and user_id=:u")
                      .param("id", id)
                      .param("u", actor));
          String zone =
              db.sql("select timezone from public.engagement_preferences where user_id=:u")
                  .param("u", actor)
                  .query(String.class)
                  .optional()
                  .orElse("UTC");
          try {
            var filters = json.readValue(row.get("filters").toString(), Filters.class);
            return discovery.search(actor, filters.search(LocalDate.now(ZoneId.of(zone))));
          } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid saved search");
          }
        });
  }

  public void remove(UUID actor, UUID id) {
    enabled(flags.engagement);
    data.as(
        actor,
        db -> {
          db.sql("delete from public.saved_searches where id=:id and user_id=:u")
              .param("id", id)
              .param("u", actor)
              .update();
          return null;
        });
  }

  public Map<String, Object> open(UUID actor, UUID id) {
    enabled(flags.any());
    return data.as(
        actor,
        db -> {
          var job =
              one(
                  db.sql(
                          "select id,booking_id,conversation_id,search_id,kind,channel from"
                              + " engagement_private.outbox where id=:id and recipient_id=:u")
                      .param("id", id)
                      .param("u", actor));
          String path = "/notifications";
          if (job.get("booking_id") != null) {
            one(
                db.sql("select id from public.bookings where id=:b")
                    .param("b", UUID.fromString(job.get("booking_id").toString())));
            path = "/booking-detail/" + job.get("booking_id");
          } else if (job.get("conversation_id") != null) {
            one(
                db.sql("select id from public.conversations where id=:c")
                    .param("c", UUID.fromString(job.get("conversation_id").toString())));
            path = "/chat/" + job.get("conversation_id");
          } else if (job.get("search_id") != null) path = "/engagement";
          var preferences =
              rows(
                  db.sql("select * from public.engagement_preferences where user_id=:u")
                      .param("u", actor));
          boolean track =
              flags.analytics
                  && !preferences.isEmpty()
                  && Boolean.TRUE.equals(preferences.getFirst().get("analytics"));
          if (track && NotificationTemplates.optional(job.get("kind").toString())) {
            var p = preferences.getFirst();
            String key =
                switch (job.get("kind").toString()) {
                  case "booking_reminder" -> "reminders";
                  case "saved_digest" -> "saved_alerts";
                  default -> "reengagement";
                };
            track =
                Boolean.TRUE.equals(p.get(key))
                    && Boolean.TRUE.equals(
                        p.get(
                            job.get("channel").equals("email")
                                ? "email_optional"
                                : "push_bookings"));
          }
          if (track) {
            db.sql(
                    "update engagement_private.outbox set"
                        + " opened_at=coalesce(opened_at,statement_timestamp()) where id=:id")
                .param("id", id)
                .update();
            db.sql("select engagement_private.record_event('notification_opened')")
                .query()
                .singleRow();
          }
          return Map.of("route", path);
        });
  }

  public Map<String, Object> export(UUID actor) {
    enabled(flags.any());
    return data.as(
        actor,
        db ->
            Map.of(
                "preferences",
                rows(
                    db.sql("select * from public.engagement_preferences where user_id=:u")
                        .param("u", actor)),
                "devices",
                rows(
                    db.sql(
                            "select device_key,platform,active,expires_at from public.push_devices"
                                + " where user_id=:u")
                        .param("u", actor)),
                "saved_searches",
                rows(
                    db.sql(
                            "select id,label,filters,alerts from public.saved_searches where"
                                + " user_id=:u")
                        .param("u", actor)),
                "contact",
                rows(
                    db.sql(
                            "select email,verified_at from engagement_private.contacts where"
                                + " user_id=:u")
                        .param("u", actor)),
                "deliveries",
                rows(
                    db.sql(
                            "select"
                                + " id,kind,channel,state,created_at,dispatched_at,delivered_at,opened_at"
                                + " from engagement_private.outbox where recipient_id=:u order by"
                                + " created_at,id limit 1000")
                        .param("u", actor)),
                "schedules",
                rows(
                    db.sql(
                            "select kind,local_day from engagement_private.schedule_sources where"
                                + " user_id=:u order by local_day limit 1000")
                        .param("u", actor)),
                "scan_state",
                rows(
                    db.sql("select next_at from engagement_private.scan_state where user_id=:u")
                        .param("u", actor)),
                "analytics",
                rows(
                    db.sql(
                            "select day,event from engagement_private.events where subject_key=:s"
                                + " order by day,event")
                        .param("s", flags.subject(actor)))));
  }

  public void delete(UUID actor) {
    enabled(flags.any());
    data.as(
        actor,
        db -> {
          db.sql("select user_id from public.engagement_preferences where user_id=:u for update")
              .param("u", actor)
              .query()
              .listOfRows();
          db.sql("delete from engagement_private.events where subject_key=:s")
              .param("s", flags.subject(actor))
              .update();
          for (String table :
              List.of(
                  "engagement_private.outbox",
                  "engagement_private.schedule_sources",
                  "engagement_private.scan_state",
                  "public.push_devices",
                  "public.saved_searches",
                  "engagement_private.contacts",
                  "public.engagement_preferences"))
            db.sql(
                    "delete from "
                        + table
                        + " where "
                        + (table.endsWith("outbox") ? "recipient_id" : "user_id")
                        + "=:u")
                .param("u", actor)
                .update();
          return null;
        });
  }

  public Map<String, Object> analytics(UUID actor) {
    enabled(flags.analytics);
    return data.as(
        actor,
        db -> {
          if (!db.sql("select engagement_private.is_admin()").query(Boolean.class).single())
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Analytics administrator access required");
          var stages =
              rows(
                  db.sql(
                      "select day,event,count(*)::int as actors from engagement_private.events"
                          + " where day>=current_date-28 group by day,event having count(*)>=5"
                          + " order by day,event"));
          var cohorts =
              rows(
                  db.sql(
                      "with first_seen as(select"
                          + " subject_key,date_trunc('week',min(day)::timestamp)::date as cohort"
                          + " from engagement_private.events group by subject_key),activity"
                          + " as(select distinct"
                          + " subject_key,date_trunc('week',day::timestamp)::date as week from"
                          + " engagement_private.events),sizes as(select cohort,count(*) as n from"
                          + " first_seen group by cohort having count(*)>=5) select"
                          + " f.cohort,a.week,count(*)::int as retained,s.n::int as cohort_size"
                          + " from first_seen f join activity a using(subject_key) join sizes s"
                          + " using(cohort) where a.week>=f.cohort group by f.cohort,a.week,s.n"
                          + " having count(*)>=5 order by f.cohort,a.week"));
          return Map.of(
              "daily_unique_stages",
              stages,
              "weekly_retention",
              cohorts,
              "minimum_cell",
              5,
              "window_days",
              90);
        });
  }
}
