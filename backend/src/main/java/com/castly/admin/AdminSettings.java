package com.castly.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AdminSettings(
    @Value("${castly.admin.enabled:false}") boolean enabled,
    @Value("${castly.admin.audit-key:}") String auditKey) {
  public AdminSettings {
    if (enabled && auditKey.length() < 32)
      throw new IllegalStateException(
          "Admin audit pseudonym key must contain at least 32 characters");
  }
}
