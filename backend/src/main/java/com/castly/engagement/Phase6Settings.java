package com.castly.engagement;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Phase6Settings {
  @Value("${castly.phase6.push-enabled:false}")
  public boolean push;

  @Value("${castly.phase6.email-enabled:false}")
  public boolean email;

  @Value("${castly.phase6.engagement-enabled:false}")
  public boolean engagement;

  @Value("${castly.phase6.analytics-enabled:false}")
  public boolean analytics;

  @Value("${castly.phase6.worker-enabled:false}")
  public boolean worker;

  @Value("${castly.phase6.push-provider:disabled}")
  public String pushProvider = "disabled";

  @Value("${castly.phase6.email-provider:disabled}")
  public String emailProvider = "disabled";

  @Value("${castly.phase6.analytics-key:}")
  public String analyticsKey = "";

  @Value("${castly.phase6.unsubscribe-key:}")
  public String unsubscribeKey = "";

  @Value("${castly.phase6.email-key:}")
  public String emailKey = "";

  @Value("${castly.phase6.webhook-secret:}")
  public String webhookSecret = "";

  @Value("${castly.phase6.email-from:}")
  public String emailFrom = "";

  @Value("${castly.phase6.api-origin:}")
  public String apiOrigin = "";

  @Value("${castly.phase6.app-origin:}")
  public String appOrigin = "";

  @jakarta.annotation.PostConstruct
  public void validate() {
    if (analytics && analyticsKey.length() < 32)
      throw new IllegalStateException("Phase 6 analytics requires a server-only HMAC key");
    if (!Set.of("disabled", "expo").contains(pushProvider)
        || !Set.of("disabled", "resend").contains(emailProvider))
      throw new IllegalStateException("Unreviewed Phase 6 provider");
    if (worker
        && email
        && !emailProvider.equals("disabled")
        && (emailKey.isBlank()
            || unsubscribeKey.length() < 32
            || webhookSecret.isBlank()
            || !emailFrom.matches("[^<>\\s]+@[^<>\\s]+\\.[^<>\\s]+")))
      throw new IllegalStateException("Approved email secrets and sender required");
    if (worker
        && (push || email)
        && (!pushProvider.equals("disabled") || !emailProvider.equals("disabled"))) {
      for (String url : List.of(appOrigin, apiOrigin)) {
        var origin = java.net.URI.create(url);
        if (!"https".equals(origin.getScheme())
            || origin.getHost() == null
            || origin.getUserInfo() != null
            || origin.getQuery() != null
            || origin.getFragment() != null
            || !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
          throw new IllegalStateException("Approved HTTPS application/API origins required");
      }
    }
  }

  public boolean any() {
    return push || email || engagement || analytics;
  }

  public String subject(UUID actor) {
    return actor == null || analyticsKey.length() < 32 ? "" : hmac(analyticsKey, actor.toString());
  }

  public static String hmac(String key, String value) {
    try {
      var m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(m.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC unavailable");
    }
  }

  public Map<String, Boolean> configuration() {
    return Map.of(
        "push",
        push,
        "email",
        email,
        "engagement",
        engagement,
        "analytics",
        analytics,
        "push_delivery",
        worker && push && pushProvider.equals("expo"),
        "email_delivery",
        worker && email && emailProvider.equals("resend"));
  }
}
