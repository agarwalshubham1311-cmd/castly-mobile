package com.castly.api;

import com.zaxxer.hikari.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

/** Reject administrator identities and URL credential overrides before opening any connection. */
@Configuration
public class DatabaseConfiguration {
  @Bean
  DataSource dataSource(
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String user,
      @Value("${spring.datasource.password}") String password) {
    validate(url, user);
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(8);
    config.setConnectionTimeout(10000);
    config.setPoolName("castly-business");
    return new HikariDataSource(config);
  }

  public static void validate(String url, String user) {
    if (!user.matches("castly_api(?:\\.[a-zA-Z0-9_-]+)?"))
      throw new IllegalStateException(
          "Use the dedicated castly_api database login, never an administrator");
    if (!url.startsWith("jdbc:postgresql://"))
      throw new IllegalStateException("Use a PostgreSQL JDBC URL");
    var parsed = URI.create(url.substring(5));
    if (parsed.getUserInfo() != null)
      throw new IllegalStateException("Keep credentials outside the JDBC URL");
    boolean local =
        java.util.List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(parsed.getHost());
    boolean verified = false;
    boolean seenTls = false;
    for (String part : (parsed.getRawQuery() == null ? "" : parsed.getRawQuery()).split("&")) {
      var pair = part.split("=", 2);
      String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
      if (key.equalsIgnoreCase("user")
          || key.equalsIgnoreCase("password")
          || key.equalsIgnoreCase("options"))
        throw new IllegalStateException("Do not override identity through the JDBC URL");
      if (key.equalsIgnoreCase("sslmode")) {
        if (seenTls || !key.equals("sslmode"))
          throw new IllegalStateException("Use one unambiguous sslmode parameter");
        seenTls = true;
        verified = pair.length == 2 && pair[1].equals("verify-full");
      }
    }
    if (!local && !verified)
      throw new IllegalStateException(
          "Remote PostgreSQL requires sslmode=verify-full and a trusted root certificate");
  }
}
