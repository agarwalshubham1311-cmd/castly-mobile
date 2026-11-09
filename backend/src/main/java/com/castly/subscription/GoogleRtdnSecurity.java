package com.castly.subscription;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class GoogleRtdnSecurity {
 @Bean @Order(1)
 SecurityFilterChain rtdnSecurity(HttpSecurity http,@Value("${castly.google.rtdn-audience:}") String audience,@Value("${castly.google.rtdn-email:}") String email) throws Exception {
  JwtDecoder decoder;
  if(audience.isBlank()||email.isBlank())decoder=token->{throw new BadJwtException("RTDN is not configured");};
  else {
   var google=NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build();
   google.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("https://accounts.google.com"),identity(audience,email)));
   decoder=google;
  }
  return http.securityMatcher("/api/v1/subscriptions/google-play/rtdn").csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(a->a.anyRequest().authenticated()).oauth2ResourceServer(o->o.jwt(j->j.decoder(decoder))).build();
 }
 public static OAuth2TokenValidator<Jwt> identity(String audience,String email){return jwt->{boolean valid=jwt.getAudience()!=null&&jwt.getAudience().contains(audience)&&email.equals(jwt.getClaimAsString("email"))&&Boolean.TRUE.equals(jwt.getClaim("email_verified"))&&jwt.getExpiresAt()!=null&&jwt.getExpiresAt().isAfter(Instant.now())&&jwt.getIssuedAt()!=null&&jwt.getIssuedAt().isAfter(Instant.now().minusSeconds(3900))&&jwt.getIssuedAt().isBefore(Instant.now().plusSeconds(60));return valid?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));};}
}
