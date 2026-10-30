package com.castly.api;

import static com.castly.api.ActorDatabase.*;
import static com.castly.api.MarketplaceController.actor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ActivityController {
  private final ActorDatabase data;

  public ActivityController(ActorDatabase data) {
    this.data = data;
  }

  private Map<String, Object> booking(JdbcClient db, UUID id, boolean lock) {
    return one(
        db.sql("select * from public.bookings where id=:id" + (lock ? " for update" : ""))
            .param("id", id));
  }

  @GetMapping("/bookings")
  public List<Map<String, Object>> bookings(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset) {
    return data.as(
        actor(jwt),
        db ->
            rows(
                db.sql(
                        "select * from public.bookings order by created_at desc,id desc limit"
                            + " :limit offset :offset")
                    .param("limit", limit)
                    .param("offset", offset)));
  }

  @GetMapping("/bookings/{id}")
  public Map<String, Object> booking(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return data.as(actor(jwt), db -> booking(db, id, false));
  }

  @PostMapping("/bookings")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.CreateBooking request) {
    return data.as(
        actor(jwt),
        db -> {
          var client =
              one(db.sql("select * from public.users where id=:me").param("me", actor(jwt)));
          if (!"client".equals(client.get("primary_role")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Client account required");
          var talent =
              one(
                  db.sql(
                          "select id,display_name from public.talent_profiles where id=:id and"
                              + " published")
                      .param("id", request.talentId()));
          if (actor(jwt).equals(request.talentId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another talent");
          return one(
              db.sql(
                      "insert into"
                          + " public.bookings(client_id,talent_id,project_type,location,requirements,proposed_date,indicative_budget,client_name,talent_name)"
                          + " values(:me,:talent,:project,:location,:requirements,:date,:budget,:clientName,:talentName)"
                          + " returning *")
                  .param("me", actor(jwt))
                  .param("talent", request.talentId())
                  .param("project", request.projectType().trim())
                  .param("location", request.location().trim())
                  .param("requirements", request.requirements().trim())
                  .param("date", request.proposedDate())
                  .param("budget", request.indicativeBudget())
                  .param("clientName", client.get("display_name"))
                  .param("talentName", talent.get("display_name")));
        });
  }

  @PostMapping("/bookings/{id}/accept")
  public Map<String, Object> accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return transition(jwt, id, "accepted");
  }

  @PostMapping("/bookings/{id}/reject")
  public Map<String, Object> reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return transition(jwt, id, "declined");
  }

  @PostMapping("/bookings/{id}/cancel")
  public Map<String, Object> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return transition(jwt, id, "cancelled");
  }

  private Map<String, Object> transition(Jwt jwt, UUID id, String target) {
    return data.as(
        actor(jwt),
        db -> {
          var row = booking(db, id, true);
          UUID me = actor(jwt);
          requireParticipant(me, row);
          String status = (String) row.get("status");
          boolean talent = me.equals(participantId(row, "talent_id"));
          boolean client = me.equals(participantId(row, "client_id"));
          if (!target.equals("cancelled") && !talent)
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Only the relevant talent can decide this request");
          if (target.equals(status)) {
            if (target.equals("accepted")) ensureConversation(db, row);
            return row;
          }
          boolean allowed =
              target.equals("cancelled")
                  ? (status.equals("requested") && client)
                      || (status.equals("accepted") && (client || talent))
                  : status.equals("requested") && talent;
          if (!allowed)
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Booking is no longer eligible for this action");
          var changed =
              one(
                  db.sql(
                          "update public.bookings set status=cast(:status as public.booking_status)"
                              + " where id=:id returning *")
                      .param("status", target)
                      .param("id", id));
          if (target.equals("accepted")) ensureConversation(db, changed);
          return changed;
        });
  }

  // JDBC UUIDs and the JSON row mapper's UUID strings must authorize identically.
  static UUID participantId(Map<String, Object> row, String column) {
    Object id = row.get(column);
    return id instanceof UUID uuid ? uuid : UUID.fromString(id.toString());
  }

  static void requireParticipant(UUID actor, Map<String, Object> row) {
    if (!actor.equals(participantId(row, "client_id"))
        && !actor.equals(participantId(row, "talent_id")))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
  }

  private Map<String, Object> ensureConversation(JdbcClient db, Map<String, Object> booking) {
    UUID client = participantId(booking, "client_id"), talent = participantId(booking, "talent_id");
    db.sql(
            "insert into public.conversations(client_id,talent_id) values(:client,:talent) on"
                + " conflict(client_id,talent_id) do nothing")
        .param("client", client)
        .param("talent", talent)
        .update();
    return one(
        db.sql("select * from public.conversations where client_id=:client and talent_id=:talent")
            .param("client", client)
            .param("talent", talent));
  }

  private static final String CONVERSATIONS =
      "select c.*,coalesce((select b.client_name from public.bookings b where"
          + " b.client_id=c.client_id and b.talent_id=c.talent_id order by b.created_at desc,b.id"
          + " desc limit 1),'Client') as client_name,coalesce((select b.talent_name from"
          + " public.bookings b where b.client_id=c.client_id and b.talent_id=c.talent_id order by"
          + " b.created_at desc,b.id desc limit 1),'Talent') as talent_name,exists(select 1 from"
          + " public.bookings b where b.client_id=c.client_id and b.talent_id=c.talent_id and"
          + " b.status='accepted') as can_send from public.conversations c";

  @GetMapping("/conversations")
  public List<Map<String, Object>> conversations(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset) {
    return data.as(
        actor(jwt),
        db ->
            rows(
                db.sql(
                        CONVERSATIONS
                            + " order by c.created_at desc,c.id desc limit :limit offset :offset")
                    .param("limit", limit)
                    .param("offset", offset)));
  }

  @GetMapping("/conversations/{id}")
  public Map<String, Object> conversation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return data.as(
        actor(jwt), db -> one(db.sql(CONVERSATIONS + " where c.id=:id").param("id", id)));
  }

  @PostMapping("/conversations")
  public Map<String, Object> open(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.OpenConversation request) {
    return data.as(
        actor(jwt),
        db -> {
          var row = booking(db, request.bookingId(), true);
          requireParticipant(actor(jwt), row);
          if (!"accepted".equals(row.get("status")))
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "An accepted booking is required");
          return ensureConversation(db, row);
        });
  }

  @GetMapping("/conversations/{id}/messages")
  public List<Map<String, Object>> messages(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(required = false) UUID before,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
    return data.as(
        actor(jwt),
        db -> {
          one(db.sql("select id from public.conversations where id=:id").param("id", id));
          if (before != null)
            one(
                db.sql("select id from public.messages where id=:before and conversation_id=:id")
                    .param("before", before)
                    .param("id", id));
          String cursor =
              before == null
                  ? ""
                  : " and (created_at,id)<(select created_at,id from public.messages where"
                      + " id=:before and conversation_id=:id)";
          var query =
              db.sql(
                      "select * from public.messages where conversation_id=:id"
                          + cursor
                          + " order by created_at desc,id desc limit :limit")
                  .param("id", id)
                  .param("limit", limit);
          if (before != null) query.param("before", before);
          var rows = new ArrayList<>(rows(query));
          Collections.reverse(rows);
          return rows;
        });
  }

  @PostMapping("/conversations/{id}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> send(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Requests.SendMessage request) {
    return data.as(
        actor(jwt),
        db -> {
          var conversation =
              one(db.sql("select * from public.conversations where id=:id").param("id", id));
          requireParticipant(actor(jwt), conversation);
          // Lock an accepted booking so send versus cancellation has a deterministic transaction
          // order.
          var accepted =
              rows(
                  db.sql(
                          "select id from public.bookings where client_id=:client and"
                              + " talent_id=:talent and status='accepted' order by id for update")
                      .param("client", UUID.fromString(conversation.get("client_id").toString()))
                      .param("talent", UUID.fromString(conversation.get("talent_id").toString())));
          if (accepted.isEmpty())
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Messaging requires an active accepted booking");
          return one(
              db.sql(
                      "insert into public.messages(conversation_id,sender_id,body)"
                          + " values(:id,:me,:body) returning *")
                  .param("id", id)
                  .param("me", actor(jwt))
                  .param("body", request.body().trim()));
        });
  }
}
