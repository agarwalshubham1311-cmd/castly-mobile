package com.castly.trust;

import static com.castly.api.ActorDatabase.*;

import com.castly.api.ActorDatabase;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewService {
  private final ActorDatabase data;
  private final TrustAccess access;
  private final boolean enabled;
  private final int editHours;

  public ReviewService(
      ActorDatabase data,
      TrustAccess access,
      @Value("${castly.trust.reviews-enabled:false}") boolean enabled,
      @Value("${castly.trust.review-edit-hours:48}") int editHours) {
    this.data = data;
    this.access = access;
    this.enabled = enabled;
    this.editHours = editHours;
    if (editHours < 0 || editHours > 720)
      throw new IllegalStateException("Review edit policy must be 0 to 720 hours");
  }

  public Map<String, Object> completion(UUID actor, UUID id, boolean confirm) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db -> {
          var booking =
              one(db.sql("select * from public.bookings where id=:id for update").param("id", id));
          if (confirm) {
            if (!Set.of("accepted", "completed").contains(booking.get("status")))
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "Only accepted bookings can be confirmed complete");
            if (!"completed".equals(booking.get("status"))) {
              db.sql(
                      "insert into public.booking_completion_confirmations(booking_id,user_id)"
                          + " values(:b,:u) on conflict do nothing")
                  .param("b", id)
                  .param("u", actor)
                  .update();
              long count =
                  db.sql(
                          "select count(*) from public.booking_completion_confirmations where"
                              + " booking_id=:b")
                      .param("b", id)
                      .query(Long.class)
                      .single();
              if (count == 2)
                booking =
                    one(
                        db.sql(
                                "update public.bookings set status='completed' where id=:b"
                                    + " returning *")
                            .param("b", id));
            }
          }
          var confirmations =
              rows(
                  db.sql(
                          "select user_id from public.booking_completion_confirmations where"
                              + " booking_id=:b")
                      .param("b", id));
          return Map.of(
              "booking",
              booking,
              "confirmed_by_me",
              confirmations.stream()
                  .anyMatch(row -> actor.toString().equals(row.get("user_id").toString())),
              "confirmation_count",
              confirmations.size());
        });
  }

  public Map<String, Object> submit(UUID actor, UUID bookingId, int rating, String body) {
    TrustAccess.enabled(enabled);
    access.rate(actor, "review_submit", 10);
    return data.as(
        actor,
        db -> {
          var booking =
              one(
                  db.sql("select * from public.bookings where id=:b for update")
                      .param("b", bookingId));
          if (!"completed".equals(booking.get("status")))
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Both participants must confirm completion before reviews");
          UUID target =
              TrustAccess.id(
                  booking,
                  actor.toString().equals(booking.get("client_id").toString())
                      ? "talent_id"
                      : "client_id");
          var old =
              rows(
                  db.sql("select * from public.user_reviews where booking_id=:b and reviewer_id=:u")
                      .param("b", bookingId)
                      .param("u", actor));
          if (!old.isEmpty()) {
            if (((Number) old.getFirst().get("rating")).intValue() == rating
                && old.getFirst().get("body").equals(body.trim())) return old.getFirst();
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Review already exists; edit the existing review");
          }
          var result =
              one(
                  db.sql(
                          "insert into"
                              + " public.user_reviews(booking_id,reviewer_id,target_id,rating,body,editable_until)"
                              + " values(:b,:u,:t,:r,:body,now()+(:h*interval '1 hour')) returning"
                              + " *")
                      .param("b", bookingId)
                      .param("u", actor)
                      .param("t", target)
                      .param("r", rating)
                      .param("body", body.trim())
                      .param("h", editHours));
          TrustAccess.audit(
              db, actor, actor, "reviews", "submitted", TrustAccess.id(result, "id"), null);
          return result;
        });
  }

  public Map<String, Object> edit(UUID actor, UUID id, int rating, String body) {
    TrustAccess.enabled(enabled);
    access.rate(actor, "review_edit", 10);
    return data.as(
        actor,
        db ->
            one(
                db.sql(
                        "update public.user_reviews set rating=:r,body=:b where id=:id and"
                            + " reviewer_id=:u returning *")
                    .param("r", rating)
                    .param("b", body.trim())
                    .param("id", id)
                    .param("u", actor)));
  }

  public Map<String, Object> publicReviews(UUID actor, UUID talent, int limit, int offset) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db -> {
          one(
              db.sql("select id from public.talent_profiles where id=:t and published")
                  .param("t", talent));
          var summary =
              one(
                  db.sql(
                          "select count(*) as review_count,round(avg(rating),2) as average_rating"
                              + " from public.user_reviews where target_id=:t and not hidden")
                      .param("t", talent));
          return Map.of(
              "summary",
              summary,
              "reviews",
              rows(
                  db.sql(
                          "select id,rating,body,created_at,updated_at,true as booking_based from"
                              + " public.user_reviews where target_id=:t and not hidden order by"
                              + " created_at desc,id desc limit :l offset :o")
                      .param("t", talent)
                      .param("l", limit)
                      .param("o", offset)));
        });
  }

  public List<Map<String, Object>> own(UUID actor, UUID booking) {
    TrustAccess.enabled(enabled);
    return data.as(
        actor,
        db ->
            rows(
                db.sql("select * from public.user_reviews where booking_id=:b and reviewer_id=:u")
                    .param("b", booking)
                    .param("u", actor)));
  }

  public Map<String, Object> moderate(UUID actor, UUID id, boolean hidden, String reason) {
    TrustAccess.enabled(enabled);
    access.rate(actor, "review_moderate", 30);
    return data.as(
        actor,
        db -> {
          TrustAccess.reviewer(db, "moderation");
          var current =
              one(
                  db.sql("select * from public.user_reviews where id=:id for update")
                      .param("id", id));
          if (actor.equals(TrustAccess.id(current, "reviewer_id"))
              || actor.equals(TrustAccess.id(current, "target_id")))
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Independent moderator required");
          if (Boolean.valueOf(hidden).equals(current.get("hidden"))) return current;

          var result =
              one(
                  db.sql("update public.user_reviews set hidden=:h where id=:id returning *")
                      .param("h", hidden)
                      .param("id", id));
          TrustAccess.audit(
              db,
              actor,
              TrustAccess.id(result, "reviewer_id"),
              "moderation",
              hidden ? "review_hidden" : "review_restored",
              id,
              reason);
          return result;
        });
  }
}
