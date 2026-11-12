package com.castly.engagement;

import com.castly.api.ActorDatabase;
import com.castly.discovery.Phase5Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@Conditional(WorkerConfiguration.ConnectionPresent.class)
public class WorkerConfiguration {
  public static class ConnectionPresent implements Condition {
    public boolean matches(
        ConditionContext context, org.springframework.core.type.AnnotatedTypeMetadata metadata) {
      var env = context.getEnvironment();
      return Boolean.parseBoolean(env.getProperty("castly.phase6.worker-enabled", "false"))
          || !env.getProperty("castly.phase6.worker-url", "").isBlank();
    }
  }

  @Bean(destroyMethod = "close")
  EngagementWorker engagementWorker(
      ActorDatabase data,
      Phase5Service discovery,
      Phase6Settings flags,
      ObjectMapper json,
      @Value("${castly.phase6.worker-url:}") String url,
      @Value("${castly.phase6.worker-user:}") String user,
      @Value("${castly.phase6.worker-password:}") String password) {
    if (!user.matches("castly_engagement_worker(?:\\.[a-zA-Z0-9_-]+)?") || password.isBlank())
      throw new IllegalStateException("Dedicated worker credentials required");
    com.castly.api.DatabaseConfiguration.validate(
        url, user.replace("castly_engagement_worker", "castly_api"));
    var pool = new com.zaxxer.hikari.HikariDataSource();
    pool.setJdbcUrl(url);
    pool.setUsername(user);
    pool.setPassword(password);
    pool.setMaximumPoolSize(2);
    pool.setConnectionTimeout(10000);
    pool.setPoolName("castly-delivery");
    try {
      return new EngagementWorker(
          pool, data, discovery, flags, new HttpDeliveryProvider(flags, json), json);
    } catch (RuntimeException e) {
      pool.close();
      throw e;
    }
  }

  @Bean
  Tick phase6Tick(EngagementWorker worker) {
    return new Tick(worker);
  }

  static class Tick {
    private final EngagementWorker worker;

    Tick(EngagementWorker worker) {
      this.worker = worker;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void run() {
      worker.tick(java.time.Instant.now());
    }
  }
}
