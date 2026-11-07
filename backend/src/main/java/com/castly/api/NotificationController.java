package com.castly.api;

import static com.castly.api.ActorDatabase.*;
import static com.castly.api.MarketplaceController.actor;

import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
  private final ActorDatabase data;
  private final NotificationService notifications;

  public NotificationController(ActorDatabase data, NotificationService notifications) {
    this.data = data;
    this.notifications = notifications;
  }

  @GetMapping("/notifications/unread-count")
  public Map<String, Object> unread(@AuthenticationPrincipal Jwt jwt) {
    return data.as(
        actor(jwt),
        db -> {
          if (!notifications.available(db)) return Map.of("enabled", false);
          var counts =
              one(
                  db.sql(
                      "select count(*)::int as unread_count,count(*) filter(where"
                          + " kind='message_received')::int as message_unread_count from"
                          + " public.notifications where recipient_id=private.request_user_id() and"
                          + " read_at is null"));
          counts.put("enabled", true);
          return counts;
        });
  }

  @GetMapping("/notifications")
  public List<Map<String, Object>> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) UUID before,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
    return data.as(
        actor(jwt),
        db -> {
          notifications.require(db);
          if (before != null)
            one(
                db.sql(
                        "select id from public.notifications where id=:id and"
                            + " recipient_id=private.request_user_id()")
                    .param("id", before));
          String cursor =
              before == null
                  ? ""
                  : " and (created_at,id)<(select created_at,id from public.notifications where"
                      + " id=:before and recipient_id=private.request_user_id())";
          var query =
              db.sql(
                      "select"
                          + " id,recipient_id,kind,booking_id,conversation_id,message_id,created_at,read_at"
                          + " from public.notifications where"
                          + " recipient_id=private.request_user_id()"
                          + cursor
                          + " order by created_at desc,id desc limit :limit")
                  .param("limit", limit);
          if (before != null) query.param("before", before);
          return rows(query);
        });
  }

  @PostMapping("/notifications/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    data.as(
        actor(jwt),
        db -> {
          notifications.require(db);
          one(
              db.sql(
                      "update public.notifications set read_at=coalesce(read_at,now()) where id=:id"
                          + " and recipient_id=private.request_user_id() returning id")
                  .param("id", id));
          return null;
        });
  }

  @PostMapping("/conversations/{id}/notifications/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void readConversation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    data.as(
        actor(jwt),
        db -> {
          var conversation =
              one(db.sql("select * from public.conversations where id=:id").param("id", id));
          ActivityController.requireParticipant(actor(jwt), conversation);
          notifications.require(db);
          db.sql(
                  "update public.notifications set read_at=now() where conversation_id=:id and"
                      + " recipient_id=private.request_user_id() and kind='message_received' and"
                      + " read_at is null")
              .param("id", id)
              .update();
          return null;
        });
  }
}
