package com.castly.subscription;

import com.castly.api.ActorDatabase;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SubscriptionRateLimiter {
  private final ActorDatabase data;

  public SubscriptionRateLimiter(ActorDatabase data) {
    this.data = data;
  }

  public void check(UUID actor, String operation, int maximum) {
    // Separate committed transaction: a rejected downstream write still consumes its attempt.
    int count =
        data.as(
            actor,
            db ->
                db.sql(
                        "insert into"
                            + " private.subscription_rate_limits(user_id,operation,window_start,attempts)"
                            + " values(:u,:o,date_trunc('minute',now()),1) on"
                            + " conflict(user_id,operation) do update set"
                            + " window_start=date_trunc('minute',now()),attempts=case when"
                            + " subscription_rate_limits.window_start=date_trunc('minute',now())"
                            + " then subscription_rate_limits.attempts+1 else 1 end returning"
                            + " attempts")
                    .param("u", actor)
                    .param("o", operation)
                    .query(Integer.class)
                    .single());
    if (count > maximum)
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Too many membership requests. Retry in a minute.");
  }
}
