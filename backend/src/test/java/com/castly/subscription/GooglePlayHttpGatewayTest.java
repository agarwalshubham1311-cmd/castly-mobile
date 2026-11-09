package com.castly.subscription;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class GooglePlayHttpGatewayTest {
 @TempDir Path directory;
 @Test void realHttpOAuthGetAndAcknowledgementContract()throws Exception{
  var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var keys=generator.generateKeyPair();
  var json=new ObjectMapper();Path credentials=directory.resolve("fixture.json");
  Files.writeString(credentials,json.writeValueAsString(Map.of("type","service_account","client_email","fixture@example.iam.gserviceaccount.com","private_key","-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----")));
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var oauthCount=new AtomicInteger();var gets=new AtomicInteger();var acks=new AtomicInteger();
  server.createContext("/oauth",exchange->{try{
   String form=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);String assertion=URLDecoder.decode(form.split("assertion=")[1],StandardCharsets.UTF_8);var jwt=SignedJWT.parse(assertion);
   assertTrue(jwt.verify(new RSASSAVerifier((RSAPublicKey)keys.getPublic())));assertEquals("https://oauth2.googleapis.com/token",jwt.getJWTClaimsSet().getAudience().getFirst());assertEquals("https://www.googleapis.com/auth/androidpublisher",jwt.getJWTClaimsSet().getStringClaim("scope"));oauthCount.incrementAndGet();
   byte[] response="{\"access_token\":\"fixture-access\",\"expires_in\":3600}".getBytes();exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
  }catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}});
  server.createContext("/applications/",exchange->{try{
   assertEquals("Bearer fixture-access",exchange.getRequestHeaders().getFirst("Authorization"));String path=exchange.getRequestURI().getRawPath();
   if(exchange.getRequestMethod().equals("GET")){assertEquals("/applications/com.castly.fixture/purchases/subscriptionsv2/tokens/token%2Fvalue",path);gets.incrementAndGet();byte[] response="{\"subscriptionState\":\"SUBSCRIPTION_STATE_ACTIVE\"}".getBytes();exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}
   else {assertEquals("/applications/com.castly.fixture/purchases/subscriptions/creator_pro/tokens/token%2Fvalue:acknowledge",path);assertEquals("{}",new String(exchange.getRequestBody().readAllBytes()));acks.incrementAndGet();exchange.sendResponseHeaders(204,-1);}
  }finally{exchange.close();}});
  server.start();try{
   URI root=URI.create("http://127.0.0.1:"+server.getAddress().getPort());var gateway=new GooglePlayHttpGateway(json,credentials.toString(),HttpClient.newHttpClient(),root.resolve("/applications/"),root.resolve("/oauth"));
   assertTrue(gateway.configured());assertEquals("SUBSCRIPTION_STATE_ACTIVE",gateway.get("com.castly.fixture","token/value").get("subscriptionState").asText());gateway.acknowledge("com.castly.fixture","creator_pro","token/value");assertEquals(1,oauthCount.get());assertEquals(1,gets.get());assertEquals(1,acks.get());
  }finally{server.stop(0);}
 }
 @Test void configurationRejectsMissingMalformedAndNonServiceAccountCredentials()throws Exception{
  var json=new ObjectMapper();assertFalse(new GooglePlayHttpGateway(json,directory.resolve("missing").toString()).configured());
  var file=directory.resolve("bad.json");Files.writeString(file,"{\"type\":\"authorized_user\"}");assertFalse(new GooglePlayHttpGateway(json,file.toString()).configured());
 }
}
