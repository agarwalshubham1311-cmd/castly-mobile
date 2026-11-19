package com.castly.discovery;

import com.castly.api.ActorDatabase;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Phase5RateLimit {
  private final ActorDatabase data;
  public Phase5RateLimit(ActorDatabase data) { this.data = data; }
  public void check(UUID actor, String operation, int maximum) {
    int attempts = data.as(actor, db -> db.sql(
        "insert into public.phase5_request_limits(user_id,operation,window_start,attempts)"
            + " values(:u,:o,date_trunc('minute',statement_timestamp()),1) on conflict(user_id,operation)"
            + " do update set window_start=date_trunc('minute',statement_timestamp()),attempts=case"
            + " when phase5_request_limits.window_start=date_trunc('minute',statement_timestamp())"
            + " then phase5_request_limits.attempts+1 else 1 end returning attempts")
        .param("u", actor).param("o", operation).query(Integer.class).single());
    if (attempts > maximum)
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many discovery requests. Retry in a minute.");
  }
}
