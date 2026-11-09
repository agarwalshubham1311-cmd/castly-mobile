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
  public SubscriptionService(ActorDatabase data,SubscriptionRepository repository,@Value("${castly.subscriptions.enabled:false}") boolean enabled) {
    this.data=data;this.repository=repository;this.enabled=enabled;
  }
  public void requireEnabled(){if(!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Memberships are not available yet");}
  public List<Map<String,Object>> plans(){requireEnabled();return data.as(null,repository::plans);}
  public Map<String,Object> current(UUID user){requireEnabled();return data.as(user,db->current(db,user));}
  public Map<String,Object> current(JdbcClient db,UUID user){
    var features=repository.effective(db,user);
    boolean pro=features.stream().anyMatch(e->"creator_pro".equals(e.get("entitlement")));
    var plan=ActorDatabase.one(db.sql("select id,name,portfolio_limit,premium_discovery from public.subscription_plans where id=:id and enabled").param("id",pro?"creator_pro":"free"));
    var result=new LinkedHashMap<String,Object>(plan);result.put("entitlements",features);
    result.put("is_creator_pro",pro);return result;
  }
  public List<Map<String,Object>> history(UUID user,int limit,int offset){requireEnabled();return data.as(user,db->ActorDatabase.rows(db.sql("select id,provider,product_id,state,expires_at,auto_renewing,acknowledged,created_at from public.subscription_orders where user_id=:u order by created_at desc,id limit :l offset :o").param("u",user).param("l",limit).param("o",offset)));}
  public List<Map<String,Object>> entitlements(UUID user){return data.as(user,db->enabled?repository.effective(db,user):ActorDatabase.rows(db.sql("select * from public.subscription_entitlements where user_id=:u and (valid_until is null or valid_until>now())").param("u",user)));}
  public void checkPortfolio(JdbcClient db,UUID user){
    if(!enabled)return;
    repository.lock(db,user);
    var configured=current(db,user).get("portfolio_limit");
    if(configured==null)return;
    int limit=((Number)configured).intValue();
    long count=db.sql("select count(*) from public.portfolio_assets where talent_id=:u").param("u",user).query(Long.class).single();
    if(count>=limit)throw new ResponseStatusException(HttpStatus.CONFLICT,"Your membership portfolio limit has been reached");
  }
}
