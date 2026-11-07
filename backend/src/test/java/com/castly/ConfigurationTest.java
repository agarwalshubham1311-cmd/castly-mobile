package com.castly;

import static org.junit.jupiter.api.Assertions.*;

import com.castly.api.DatabaseConfiguration;
import com.zaxxer.hikari.HikariConfig;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ConfigurationTest {
  @Configuration(proxyBeanMethods = false)
  static class NoNetworkConfiguration extends DatabaseConfiguration {
    @Bean
    @Override
    protected DataSource dataSource(HikariConfig config) {
      return org.mockito.Mockito.mock(DataSource.class);
    }
  }

  @Test
  void poolSettingsActuallyBindWithoutOverridingCredentialsTlsOrDriverProperties() {
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(NoNetworkConfiguration.class)
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://db.example/test?sslmode=verify-full",
                "spring.datasource.username=castly_api",
                "spring.datasource.password=synthetic-fixture");
    runner.run(
        context -> {
          assertNull(context.getStartupFailure());
          HikariConfig config = context.getBean(HikariConfig.class);
          assertEquals(8, config.getMaximumPoolSize());
          assertEquals(10000, config.getConnectionTimeout());
        });
    runner
        .withPropertyValues(
            "spring.datasource.hikari.maximum-pool-size=3",
            "spring.datasource.hikari.connection-timeout=1700")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              HikariConfig config = context.getBean(HikariConfig.class);
              assertEquals(3, config.getMaximumPoolSize());
              assertEquals(1700, config.getConnectionTimeout());
              assertEquals(
                  "jdbc:postgresql://db.example/test?sslmode=verify-full", config.getJdbcUrl());
              assertEquals("castly_api", config.getUsername());
              assertEquals("synthetic-fixture", config.getPassword());
              assertTrue(config.getDataSourceProperties().isEmpty());
              config.validate();
              assertEquals(3, config.getMinimumIdle());
              assertEquals(1800000, config.getMaxLifetime());
              assertEquals(120000, config.getKeepaliveTime());
            });
  }

  @Test
  void rejectsAdministratorAndUrlOverridesBeforeConnection() {
    for (String user : new String[] {"postgres", "supabase_admin", "service_role"})
      assertThrows(
          IllegalStateException.class,
          () -> DatabaseConfiguration.validate("jdbc:postgresql://localhost:55432/postgres", user));
    assertThrows(
        IllegalStateException.class,
        () ->
            DatabaseConfiguration.validate(
                "jdbc:postgresql://localhost/db?user=postgres", "castly_api"));
    assertThrows(
        IllegalStateException.class,
        () ->
            DatabaseConfiguration.validate(
                "jdbc:postgresql://localhost/db?options=-crole=postgres", "castly_api"));
  }

  @Test
  void requiresVerifiedRemoteTlsAndAllowsExplicitLocalDatabase() {
    assertThrows(
        IllegalStateException.class,
        () ->
            DatabaseConfiguration.validate(
                "jdbc:postgresql://db.example/postgres?sslmode=verify-full&sslmode=disable",
                "castly_api"));
    assertThrows(
        IllegalStateException.class,
        () ->
            DatabaseConfiguration.validate(
                "jdbc:postgresql://db.example/postgres?sslmode=require", "castly_api"));
    assertDoesNotThrow(
        () ->
            DatabaseConfiguration.validate(
                "jdbc:postgresql://db.example/postgres?sslmode=verify-full", "castly_api.project"));
    assertDoesNotThrow(
        () ->
            DatabaseConfiguration.validate(
                "jdbc:postgresql://localhost:55432/castly_spring_test", "castly_api"));
  }
}
