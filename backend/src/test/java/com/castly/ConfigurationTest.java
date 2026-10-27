package com.castly;

import static org.junit.jupiter.api.Assertions.*;

import com.castly.api.DatabaseConfiguration;
import org.junit.jupiter.api.Test;

class ConfigurationTest {
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
