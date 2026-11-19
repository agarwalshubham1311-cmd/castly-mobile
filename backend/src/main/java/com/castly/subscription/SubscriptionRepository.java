package com.castly.subscription;

import static com.castly.api.ActorDatabase.*;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SubscriptionRepository {
  public List<Map<String, Object>> plans(JdbcClient db) {
    return rows(
        db.sql(
            "select id,name,portfolio_limit,premium_discovery,google_product_id,google_base_plan_id"
                + " from public.subscription_plans where enabled order by premium_discovery,id"));
  }

  public List<Map<String, Object>> effective(JdbcClient db, UUID user) {
    return rows(
        db.sql(
                "select coalesce(e.id,g.id) as"
                    + " id,g.user_id,g.entitlement,g.valid_until,coalesce(e.created_at,g.created_at)"
                    + " as created_at from (select min(id::text)::uuid as"
                    + " id,user_id,entitlement,min(created_at) as created_at,case when"
                    + " bool_or(expires_at is null) then null else max(expires_at) end as"
                    + " valid_until from public.subscription_grants where user_id=:u and"
                    + " starts_at<=statement_timestamp() and (expires_at is null or"
                    + " expires_at>statement_timestamp()) and revoked_at is null group by"
                    + " user_id,entitlement) g left join public.subscription_entitlements e on"
                    + " e.user_id=g.user_id and e.entitlement=g.entitlement")
            .param("u", user));
  }

  public void sync(JdbcClient db, UUID user) {
    // Preserve the old read contract without treating a stale projection as authority.
    db.sql(
            "insert into public.subscription_entitlements(user_id,entitlement,valid_until) select"
                + " user_id,entitlement,case when bool_or(expires_at is null and revoked_at is null"
                + " and starts_at<=statement_timestamp()) then null else coalesce(max(expires_at)"
                + " filter(where revoked_at is null and starts_at<=statement_timestamp() and"
                + " expires_at>statement_timestamp()),statement_timestamp()) end from"
                + " public.subscription_grants where user_id=:u group by user_id,entitlement on"
                + " conflict(user_id,entitlement) do update set valid_until=excluded.valid_until")
        .param("u", user)
        .update();
    db.sql(
            "insert into public.subscription_discovery_eligibility(user_id,eligible,valid_until)"
                + " select :u,count(*)>0,case when bool_or(expires_at is null) then null else"
                + " max(expires_at) end from public.subscription_grants where user_id=:u and"
                + " entitlement='creator_pro' and starts_at<=statement_timestamp() and (expires_at"
                + " is null or expires_at>statement_timestamp()) and revoked_at is null on"
                + " conflict(user_id) do update set"
                + " eligible=excluded.eligible,valid_until=excluded.valid_until,updated_at=statement_timestamp()")
        .param("u", user)
        .update();
  }

  public void lock(JdbcClient db, UUID user) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:u,31))")
        .param("u", user.toString())
        .query()
        .singleRow();
  }

  public void audit(
      JdbcClient db, UUID user, UUID actor, String action, UUID resource, String reason) {
    db.sql(
            "insert into public.subscription_audit_logs(user_id,actor_id,action,resource_id,reason)"
                + " values(:u,:a,:action,:r,:reason)")
        .param("u", user)
        .param("a", actor)
        .param("action", action)
        .param("r", resource)
        .param("reason", reason)
        .update();
  }
}
