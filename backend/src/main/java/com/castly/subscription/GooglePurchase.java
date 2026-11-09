package com.castly.subscription;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Strict interpretation of subscriptionsv2; unknown states, multi-item bundles and unbound accounts fail closed. */
public record GooglePurchase(String product,String basePlan,String state,Instant expiry,boolean autoRenewing,boolean acknowledged,String orderId,String linkedToken) {
 public static GooglePurchase parse(JsonNode body,String expectedAccount,Map<String,String> products){
  try{
   if(!expectedAccount.equals(body.path("externalAccountIdentifiers").path("obfuscatedExternalAccountId").asText()))throw invalid();
   var lines=body.path("lineItems");if(!lines.isArray()||lines.size()!=1)throw invalid();
   var line=lines.get(0);String product=line.path("productId").asText(),base=line.path("offerDetails").path("basePlanId").asText();
   if(!products.containsKey(product)||!products.get(product).equals(base))throw invalid();
   String state=switch(body.path("subscriptionState").asText()){
    case "SUBSCRIPTION_STATE_PENDING"->"pending";
    case "SUBSCRIPTION_STATE_ACTIVE"->"active";
    case "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"->"grace";
    case "SUBSCRIPTION_STATE_ON_HOLD"->"on_hold";
    case "SUBSCRIPTION_STATE_PAUSED"->"paused";
    case "SUBSCRIPTION_STATE_CANCELED"->"cancelled";
    case "SUBSCRIPTION_STATE_EXPIRED","SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED"->"expired";
    default->throw invalid();};
   Instant expiry=line.hasNonNull("expiryTime")?Instant.parse(line.get("expiryTime").asText()):null;
   if(!state.equals("pending")&&expiry==null)throw invalid();
   String acknowledgement=body.path("acknowledgementState").asText();
   if(!Set.of("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED","ACKNOWLEDGEMENT_STATE_PENDING").contains(acknowledgement))throw invalid();
   String order=line.path("latestSuccessfulOrderId").asText(body.path("latestOrderId").asText());
   if(order.length()>256)throw invalid();
   return new GooglePurchase(product,base,state,expiry,line.path("autoRenewingPlan").path("autoRenewEnabled").asBoolean(false),acknowledgement.equals("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"),order.isBlank()?null:order,body.path("linkedPurchaseToken").asText(null));
  }catch(ResponseStatusException e){throw e;}catch(Exception e){throw invalid();}
 }
 public boolean eligible(Instant now){return Set.of("active","grace","cancelled").contains(state)&&expiry!=null&&expiry.isAfter(now);}
 private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Google Play purchase could not be verified for this account and plan");}
}
