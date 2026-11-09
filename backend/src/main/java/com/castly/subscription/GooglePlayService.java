package com.castly.subscription;

import static com.castly.api.ActorDatabase.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GooglePlayService {
 private final SubscriptionService subscriptions;
 private final SubscriptionRateLimiter limiter;
 private final GooglePlayGateway google;
 private final ObjectMapper json;
 private final boolean enabled;
 private final String packageName,associationKey;
 public GooglePlayService(SubscriptionService subscriptions,SubscriptionRateLimiter limiter,GooglePlayGateway google,ObjectMapper json,@Value("${castly.google.enabled:false}") boolean enabled,@Value("${castly.google.package-name:}") String packageName,@Value("${castly.google.account-key:}") String associationKey){this.subscriptions=subscriptions;this.limiter=limiter;this.google=google;this.json=json;this.enabled=enabled;this.packageName=packageName;this.associationKey=associationKey;}
 public boolean configured(){return enabled&&google.configured()&&packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")&&associationKey.length()>=32;}
 private void require(){subscriptions.requireEnabled();if(!configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Google Play purchases are not configured");}
 public String accountId(UUID user){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(associationKey.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(user.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Account association is unavailable");}}
 public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException();}}
 private Map<String,String> products(JdbcClient db){var result=new HashMap<String,String>();for(var p:subscriptions.repository.plans(db))if("creator_pro".equals(p.get("id"))&&p.get("google_product_id")!=null)result.put(p.get("google_product_id").toString(),p.get("google_base_plan_id").toString());return result;}
 public Map<String,Object> configuration(UUID actor){subscriptions.requireEnabled();return subscriptions.data.as(actor,db->{
  boolean creator=db.sql("select primary_role::text from public.users where id=:u").param("u",actor).query(String.class).optional().orElse("").equals("talent");
  var products=products(db);boolean ready=configured()&&creator&&!products.isEmpty();
  return Map.of("enabled",ready,"package_name",ready?packageName:"","obfuscated_account_id",ready?accountId(actor):"","products",ready?products:Map.of());
 });}
 public Map<String,Object> verify(UUID actor,String token){require();limiter.check(actor,"google_verify",15);return subscriptions.data.as(actor,db->reconcile(db,actor,token,false));}
 static void verifiedContext(JdbcClient db){db.sql("select set_config('castly.subscription_verified','yes',true)").query().singleRow();}
 private Map<String,Object> reconcile(JdbcClient db,UUID actor,String token,boolean revoke){
  verifiedContext(db);subscriptions.repository.lock(db,actor);
  if(!db.sql("select primary_role::text from public.users where id=:u").param("u",actor).query(String.class).optional().orElse("").equals("talent"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Creator Pro is available to creator accounts");
  String tokenHash=hash(token);
  db.sql("select pg_advisory_xact_lock(hashtextextended(:h,53))").param("h",tokenHash).query().singleRow();
  var owner=db.sql("select user_id from private.subscription_purchase_owners where token_hash=:h").param("h",tokenHash).query(UUID.class).optional();
  if(owner.isPresent()&&!owner.get().equals(actor))throw new ResponseStatusException(HttpStatus.CONFLICT,"This purchase is associated with another Castly account");
  if(db.sql("select count(*) from private.subscription_replaced_tokens where token_hash=:h").param("h",tokenHash).query(Long.class).single()>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"This purchase has been replaced. Restore the current subscription.");
  var purchase=GooglePurchase.parse(google.get(packageName,token),accountId(actor),products(db));
  if(purchase.linkedToken()!=null){
   String oldHash=hash(purchase.linkedToken());if(oldHash.equals(tokenHash))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid replacement purchase");
   var oldOwner=db.sql("select user_id from private.subscription_purchase_owners where token_hash=:h").param("h",oldHash).query(UUID.class).optional();
   if(oldOwner.isPresent()&&!oldOwner.get().equals(actor))throw new ResponseStatusException(HttpStatus.CONFLICT,"Replacement purchase belongs to another account");
   // A pending replacement must not remove the previous paid entitlement.
   if(purchase.eligible(Instant.now())){
    db.sql("insert into private.subscription_replaced_tokens(token_hash,replacement_hash,user_id) values(:h,:r,:u) on conflict(token_hash) do nothing").param("h",oldHash).param("r",tokenHash).param("u",actor).update();
    db.sql("update public.subscription_orders set superseded_by_hash=:r,updated_at=now() where token_hash=:h").param("h",oldHash).param("r",tokenHash).update();
    db.sql("update public.subscription_grants set revoked_at=coalesce(revoked_at,now()) where user_id=:u and source_key=:key").param("u",actor).param("key","google:"+oldHash).update();
   }
  }
  var existing=rows(db.sql("select id,state from public.subscription_orders where token_hash=:h for update").param("h",tokenHash));
  boolean revoked=revoke||(!existing.isEmpty()&&"revoked".equals(existing.getFirst().get("state")));
  boolean eligible=purchase.eligible(Instant.now())&&!revoked;
  boolean acknowledged=purchase.acknowledged();
  if(eligible&&!acknowledged){google.acknowledge(packageName,purchase.product(),token);acknowledged=true;}
  db.sql("insert into private.subscription_purchase_owners(token_hash,user_id) values(:h,:u) on conflict(token_hash) do nothing").param("h",tokenHash).param("u",actor).update();
  String state=revoked?"revoked":purchase.state();
  var order=one(db.sql("insert into public.subscription_orders(user_id,token_hash,product_id,provider_order_id,state,expires_at,auto_renewing,acknowledged) values(:u,:h,:p,:order,:s,:expiry,:renew,:ack) on conflict(token_hash) do update set provider_order_id=excluded.provider_order_id,state=excluded.state,expires_at=excluded.expires_at,auto_renewing=excluded.auto_renewing,acknowledged=excluded.acknowledged,updated_at=now() returning id").param("u",actor).param("h",tokenHash).param("p",purchase.product()).param("order",purchase.orderId()).param("s",state).param("expiry",purchase.expiry()==null?null:Timestamp.from(purchase.expiry())).param("renew",purchase.autoRenewing()).param("ack",acknowledged));
  UUID id=UUID.fromString(order.get("id").toString());
  if(eligible){
   db.sql("insert into public.subscription_grants(user_id,entitlement,source,source_key,expires_at) values(:u,'creator_pro','google_play',:key,:expiry) on conflict(source_key) do update set expires_at=excluded.expires_at,revoked_at=null").param("u",actor).param("key","google:"+tokenHash).param("expiry",Timestamp.from(purchase.expiry())).update();
  }else db.sql("update public.subscription_grants set revoked_at=coalesce(revoked_at,now()) where user_id=:u and source_key=:key").param("u",actor).param("key","google:"+tokenHash).update();
  String eventKey=hash(tokenHash+":"+state+":"+purchase.expiry()+":"+purchase.autoRenewing()+":"+purchase.orderId());
  int added=db.sql("insert into public.subscription_events(user_id,event_key,order_id,event_type) values(:u,:key,:id,:s) on conflict(event_key) do nothing").param("u",actor).param("key",eventKey).param("id",id).param("s",state).update();
  if(added>0)subscriptions.repository.audit(db,actor,actor,"google_"+state,id,"Verified through Google Play Developer API");
  subscriptions.repository.sync(db,actor);
  var result=new LinkedHashMap<String,Object>(subscriptions.current(db,actor));
  result.put("purchase_state",state);result.put("purchase_acknowledged",acknowledged);return result;
 }
 public void rtdn(String messageId,String encoded){
  require();JsonNode notification;
  try{byte[] bytes=Base64.getDecoder().decode(encoded);if(bytes.length>16384)throw new IllegalArgumentException();notification=json.readTree(bytes);}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Google notification");}
  if(!packageName.equals(notification.path("packageName").asText()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unexpected application package");
  if(notification.has("testNotification"))return;
  var change=notification.has("subscriptionNotification")?notification.get("subscriptionNotification"):notification.get("voidedPurchaseNotification");
  if(change==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported Google notification");
  String token=change.path("purchaseToken").asText();if(token.length()<16||token.length()>4096||token.chars().anyMatch(Character::isWhitespace))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Google notification");
  String payloadHash=hash(encoded);
  UUID owner=subscriptions.data.as(null,db->db.sql("select user_id from private.subscription_purchase_owners where token_hash=:h").param("h",hash(token)).query(UUID.class).optional().orElse(null));
  subscriptions.data.as(owner,db->{
   verifiedContext(db);
   int inserted=db.sql("insert into private.subscription_rtdn_receipts(message_id,payload_hash) values(:id,:h) on conflict(message_id) do nothing").param("id",messageId).param("h",payloadHash).update();
   if(inserted==0){String previous=db.sql("select payload_hash from private.subscription_rtdn_receipts where message_id=:id").param("id",messageId).query(String.class).single();if(!previous.equals(payloadHash))throw new ResponseStatusException(HttpStatus.CONFLICT,"Notification identifier was reused");return null;}
   if(owner!=null)reconcile(db,owner,token,notification.has("voidedPurchaseNotification")||change.path("notificationType").asInt()==12);
   return null;
  });
 }
}
