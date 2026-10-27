package com.castly.security;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
@Configuration @EnableWebSecurity public class ApiSecurity {
 @Bean SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
  return http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated())
   .oauth2ResourceServer(o -> o.jwt(j -> {})).build();
 }
}
