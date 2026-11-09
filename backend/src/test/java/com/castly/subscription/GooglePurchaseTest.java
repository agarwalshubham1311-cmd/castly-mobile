package com.castly.subscription;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jwt.Jwt;
class GooglePurchaseTest {
 static final ObjectMapper JSON=new ObjectMapper();
 static JsonNode fixture(String state,String account,String expiry){return JSON.createObjectNode().put("subscriptionState","SUBSCRIPTION_STATE_"+state).put("acknowledgementState","ACKNOWLEDGEMENT_STATE_PENDING").set("externalAccountIdentifiers",JSON.createObjectNode().put("obfuscatedExternalAccountId",account));}
 static JsonNode purchase(String state,String expiry)throws Exception{
  return JSON.readTree("{\"subscriptionState\":\"SUBSCRIPTION_STATE_"+state+"\",\"acknowledgementState\":\"ACKNOWLEDGEMENT_STATE_PENDING\",\"externalAccountIdentifiers\":{\"obfuscatedExternalAccountId\":\"bound-account\"},\"lineItems\":[{\"productId\":\"creator_pro\",\"offerDetails\":{\"basePlanId\":\"annual\"},\"expiryTime\":\""+expiry+"\",\"autoRenewingPlan\":{\"autoRenewEnabled\":true},\"latestSuccessfulOrderId\":\"GPA.test..0\"}]}");
 }
 @ParameterizedTest @CsvSource({"ACTIVE,true","IN_GRACE_PERIOD,true","CANCELED,true","PENDING,false","ON_HOLD,false","PAUSED,false","EXPIRED,false","PENDING_PURCHASE_CANCELED,false"})
 void stateSemantics(String state,boolean eligible)throws Exception{assertEquals(eligible,GooglePurchase.parse(purchase(state,"2030-01-01T00:00:00Z"),"bound-account",Map.of("creator_pro","annual")).eligible(Instant.parse("2026-11-09T00:00:00Z")));}
 @Test void expiryAndBindingFailClosed()throws Exception{
  var body=purchase("ACTIVE","2020-01-01T00:00:00Z");assertFalse(GooglePurchase.parse(body,"bound-account",Map.of("creator_pro","annual")).eligible(Instant.now()));
  assertThrows(Exception.class,()->GooglePurchase.parse(body,"different",Map.of("creator_pro","annual")));
  assertThrows(Exception.class,()->GooglePurchase.parse(body,"bound-account",Map.of("creator_pro","monthly")));
  assertThrows(Exception.class,()->GooglePurchase.parse(JSON.readTree("{}"),"bound-account",Map.of("creator_pro","annual")));
  var array=(com.fasterxml.jackson.databind.node.ArrayNode)body.get("lineItems");array.add(array.get(0).deepCopy());
  assertThrows(Exception.class,()->GooglePurchase.parse(body,"bound-account",Map.of("creator_pro","annual")));
  assertThrows(Exception.class,()->GooglePurchase.parse(purchase("UNKNOWN","2030-01-01T00:00:00Z"),"bound-account",Map.of("creator_pro","annual")));
 }
 @Test void oidcIdentityRequiresExactAudienceVerifiedEmailAndRecentIssuedTime(){
  var validator=GoogleRtdnSecurity.identity("https://api.example/rtdn","push@example.iam.gserviceaccount.com");
  var good=Jwt.withTokenValue("fixture").header("alg","RS256").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).audience(java.util.List.of("https://api.example/rtdn")).claim("email","push@example.iam.gserviceaccount.com").claim("email_verified",true).build();
  assertFalse(validator.validate(good).hasErrors());
  for(String field:java.util.List.of("email","email_verified","aud")){
   var claims=new java.util.HashMap<>(good.getClaims());claims.put(field,field.equals("email_verified")?false:field.equals("aud")?java.util.List.of("wrong"):"wrong@example.com");
   assertTrue(validator.validate(new Jwt("fixture",good.getIssuedAt(),good.getExpiresAt(),good.getHeaders(),claims)).hasErrors());
  }
 }
 @Test void youtubeAliasesHaveOneClaimIdentity(){
  String canonical="https://youtube.com/watch?v=abc12345";
  assertEquals(canonical,PromotionService.canonicalUrl(canonical+"&t=30"));
  assertEquals(canonical,PromotionService.canonicalUrl("https://youtu.be/abc12345"));
  assertEquals(canonical,PromotionService.canonicalUrl("https://www.youtube.com/shorts/abc12345"));
 }
}
