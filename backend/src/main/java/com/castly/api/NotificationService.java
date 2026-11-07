package com.castly.api;

import static com.castly.api.ActorDatabase.*;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class NotificationService {
  private final boolean enabled;

  public NotificationService(@Value("${castly.notifications.enabled:false}") boolean enabled) {
    this.enabled = enabled;
  }

  public boolean available(JdbcClient db) {
    return enabled
        && db.sql("select to_regclass('public.notifications') is not null")
            .query(Boolean.class)
            .single();
  }

  public void require(JdbcClient db) {
    if (!available(db))
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "In-app notifications are not enabled yet. Bookings and messages remain available.");
  }

  public Map<String, Object> decorate(JdbcClient db, Map<String, Object> conversation) {
    if (available(db))
      conversation.put(
          "unread_notifications",
          db.sql(
                  "select count(*)::int from public.notifications where conversation_id=:id and"
                      + " recipient_id=private.request_user_id() and read_at is null")
              .param("id", UUID.fromString(conversation.get("id").toString()))
              .query(Integer.class)
              .single());
    return conversation;
  }

  public List<Map<String, Object>> decorate(
      JdbcClient db, List<Map<String, Object>> conversations) {
    if (!available(db)) return conversations;
    var counts =
        rows(
            db.sql(
                "select conversation_id,count(*)::int as total from public.notifications where"
                    + " recipient_id=private.request_user_id() and conversation_id is not null and"
                    + " read_at is null group by conversation_id"));
    var byId = new HashMap<String, Object>();
    for (var count : counts) byId.put(count.get("conversation_id").toString(), count.get("total"));
    for (var row : conversations)
      row.put("unread_notifications", byId.getOrDefault(row.get("id").toString(), 0));
    return conversations;
  }
}
