package com.castly.subscription;

import com.castly.api.ActorDatabase;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubscriptionService {
  final ActorDatabase data;
  final SubscriptionRepository repository;
  private final boolean enabled;

  public SubscriptionService(
      ActorDatabase data,
      SubscriptionRepository repository,
      @Value("${castly.subscriptions.enabled:false}") boolean enabled) {
    this.data = data;
    this.repository = repository;
    this.enabled = enabled;
  }

  public String discoveryField() {
    return enabled
        ? "exists(select 1 from public.subscription_discovery_eligibility e where"
              + " e.user_id=talent_profiles.id and e.eligible and (e.valid_until is null or"
              + " e.valid_until>statement_timestamp()) and exists(select 1 from"
              + " public.subscription_plans p where p.id='creator_pro' and p.premium_discovery)) as"
              + " premium_discovery_eligible"
        : "false as premium_discovery_eligible";
  }

  public void requireEnabled() {
    if (!enabled)
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Memberships are not available yet");
  }

  public List<Map<String, Object>> plans() {
    requireEnabled();
    return data.as(null, repository::plans);
  }

  public Map<String, Object> current(UUID user) {
    requireEnabled();
    return data.as(user, db -> current(db, user));
  }

  public Map<String, Object> current(JdbcClient db, UUID user) {
    var features = repository.effective(db, user);
    boolean pro = features.stream().anyMatch(e -> "creator_pro".equals(e.get("entitlement")));
    var plan =
        ActorDatabase.one(
            db.sql(
                    "select id,name,portfolio_limit,premium_discovery,premium_features from"
                        + " public.subscription_plans where id=:id and enabled")
                .param("id", pro ? "creator_pro" : "free"));
    var result = new LinkedHashMap<String, Object>(plan);
    result.put("entitlements", features);
    result.put("is_creator_pro", pro);
    return result;
  }

  public List<Map<String, Object>> history(UUID user, int limit, int offset) {
    requireEnabled();
    return data.as(
        user,
        db ->
            ActorDatabase.rows(
                db.sql(
                        "select"
                            + " id,provider,product_id,state,expires_at,auto_renewing,acknowledged,created_at"
                            + " from public.subscription_orders where user_id=:u order by"
                            + " created_at desc,id limit :l offset :o")
                    .param("u", user)
                    .param("l", limit)
                    .param("o", offset)));
  }

  public List<Map<String, Object>> entitlements(UUID user) {
    return data.as(
        user,
        db ->
            enabled
                ? repository.effective(db, user)
                : ActorDatabase.rows(
                    db.sql(
                            "select * from public.subscription_entitlements where user_id=:u and"
                                + " (valid_until is null or valid_until>statement_timestamp())")
                        .param("u", user)));
  }

  public boolean hasEntitlement(UUID user, String feature) {
    requireEnabled();
    return data.as(
        user,
        db -> {
          var current = current(db, user);
          return repository.effective(db, user).stream()
                  .anyMatch(e -> feature.equals(e.get("entitlement")))
              || (Boolean.TRUE.equals(current.get("is_creator_pro"))
                  && ((List<?>) current.get("premium_features")).contains(feature));
        });
  }

  public void checkPortfolio(JdbcClient db, UUID user) {
    if (!enabled) return;
    repository.lock(db, user);
    var configured = current(db, user).get("portfolio_limit");
    if (configured == null) return;
    int limit = ((Number) configured).intValue();
    long count =
        db.sql("select count(*) from public.portfolio_assets where talent_id=:u")
            .param("u", user)
            .query(Long.class)
            .single();
    if (count >= limit)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Your membership portfolio limit has been reached");
  }
}
