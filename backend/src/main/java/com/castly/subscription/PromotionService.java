package com.castly.subscription;
import static com.castly.api.ActorDatabase.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
@Service
public class PromotionService {
 private final SubscriptionService subscriptions;
 private final SubscriptionRateLimiter limiter;
 public PromotionService(SubscriptionService subscriptions,SubscriptionRateLimiter limiter){this.subscriptions=subscriptions;this.limiter=limiter;}
 public static String canonicalUrl(String value){
  try {
   URI u=URI.create(value.trim());String host=u.getHost();
   if(!"https".equals(u.getScheme())||u.getUserInfo()!=null||u.getPort()!=-1||host==null||!Set.of("instagram.com","www.instagram.com","youtube.com","www.youtube.com","youtu.be","tiktok.com","www.tiktok.com","x.com","www.x.com").contains(host.toLowerCase(Locale.ROOT))||u.getRawPath()==null||u.getRawPath().length()<2)throw new IllegalArgumentException();
   host=host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");
   String path=u.getRawPath().replaceFirst("/$","");
   // YouTube watch video identity is its v parameter, not tracking parameters.
   if(host.equals("youtube.com")&&path.equals("/watch")){
    String id=Arrays.stream(Optional.ofNullable(u.getRawQuery()).orElse("").split("&")).filter(p->p.matches("v=[A-Za-z0-9_-]{6,100}")).findFirst().orElseThrow();
    return "https://"+host+path+"?"+id;
   }
   return "https://"+host+path;
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a public HTTPS post URL from Instagram, YouTube, TikTok or X");}
 }
 public Map<String,Object> submit(UUID actor,String url){
  subscriptions.requireEnabled();String canonical=canonicalUrl(url);limiter.check(actor,"promotion_submit",5);
  return subscriptions.data.as(actor,db->{
   subscriptions.repository.lock(db,actor);
   var role=db.sql("select primary_role::text from public.users where id=:u").param("u",actor).query(String.class).optional();
   if(!role.orElse("").equals("talent"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"The founding offer is for creators");
   var existing=rows(db.sql("select * from public.promotion_claims where user_id=:u").param("u",actor));
   if(!existing.isEmpty()){
    if(existing.getFirst().get("post_url").equals(canonical))return existing.getFirst();
    throw new ResponseStatusException(HttpStatus.CONFLICT,"A founding offer claim already exists for your account");
   }
   return one(db.sql("insert into public.promotion_claims(user_id,post_url) values(:u,:url) returning *").param("u",actor).param("url",canonical));
  });
 }
 public List<Map<String,Object>> own(UUID actor){subscriptions.requireEnabled();return subscriptions.data.as(actor,db->rows(db.sql("select id,post_url,status,reason,created_at,reviewed_at from public.promotion_claims where user_id=:u").param("u",actor)));}
 public List<Map<String,Object>> queue(UUID actor,int limit,int offset){subscriptions.requireEnabled();return subscriptions.data.as(actor,db->{admin(db);return rows(db.sql("select * from public.promotion_claims where status='pending' order by created_at,id limit :l offset :o").param("l",limit).param("o",offset));});}
 private void admin(org.springframework.jdbc.core.simple.JdbcClient db){if(!db.sql("select private.is_subscription_admin()").query(Boolean.class).single())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Subscription reviewer access is required");}
 public Map<String,Object> review(UUID actor,UUID id,boolean approve,String reason){
  subscriptions.requireEnabled();limiter.check(actor,"promotion_review",30);
  return subscriptions.data.as(actor,db->{
   admin(db);
   var claim=one(db.sql("select * from public.promotion_claims where id=:id for update").param("id",id));
   UUID owner=UUID.fromString(claim.get("user_id").toString());
   if(owner.equals(actor))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Reviewers cannot approve their own claim");
   String target=approve?"approved":"rejected";
   if(!claim.get("status").equals("pending")){
    if(claim.get("status").equals(target))return claim;
    throw new ResponseStatusException(HttpStatus.CONFLICT,"The claim has already been reviewed");
   }
   subscriptions.repository.lock(db,owner);
   var result=one(db.sql("update public.promotion_claims set status=:s,reason=:r,reviewer_id=:a,reviewed_at=now() where id=:id returning *").param("s",target).param("r",reason.trim()).param("a",actor).param("id",id));
   if(approve){
    db.sql("insert into public.subscription_grants(user_id,entitlement,source,source_key,starts_at,expires_at) values(:u,'creator_pro','promotion',:key,now(),now()+interval '12 months') on conflict(source_key) do nothing").param("u",owner).param("key","promotion:"+id).update();
    subscriptions.repository.sync(db,owner);
   }
   subscriptions.repository.audit(db,owner,actor,"promotion_"+target,id,reason.trim());
   return result;
  });
 }
}
