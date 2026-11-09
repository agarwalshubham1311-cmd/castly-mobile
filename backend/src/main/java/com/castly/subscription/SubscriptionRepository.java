package com.castly.subscription;

import static com.castly.api.ActorDatabase.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SubscriptionRepository {
  public List<Map<String,Object>> plans(JdbcClient db) {
    return rows(db.sql("select id,name,portfolio_limit,premium_discovery,google_product_id,google_base_plan_id from public.subscription_plans where enabled order by premium_discovery,id"));
  }
  public List<Map<String,Object>> effective(JdbcClient db, UUID user) {
    return rows(db.sql("select entitlement,case when bool_or(expires_at is null) then null else max(expires_at) end as valid_until from public.subscription_grants where user_id=:u and starts_at<=now() and (expires_at is null or expires_at>now()) and revoked_at is null group by entitlement").param("u",user));
  }
  public void sync(JdbcClient db, UUID user) {
    // Preserve the old read contract without treating a stale projection as authority.
    db.sql("insert into public.subscription_entitlements(user_id,entitlement,valid_until) select user_id,entitlement,case when bool_or(expires_at is null and revoked_at is null and starts_at<=now()) then null else coalesce(max(expires_at) filter(where revoked_at is null and starts_at<=now() and expires_at>now()),now()) end from public.subscription_grants where user_id=:u group by user_id,entitlement on conflict(user_id,entitlement) do update set valid_until=excluded.valid_until").param("u",user).update();
  }
  public void lock(JdbcClient db, UUID user) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:u,31))").param("u",user.toString()).query().singleRow();
  }
  public void audit(JdbcClient db,UUID user,UUID actor,String action,UUID resource,String reason) {
    db.sql("insert into public.subscription_audit_logs(user_id,actor_id,action,resource_id,reason) values(:u,:a,:action,:r,:reason)").param("u",user).param("a",actor).param("action",action).param("r",resource).param("reason",reason).update();
  }
}
