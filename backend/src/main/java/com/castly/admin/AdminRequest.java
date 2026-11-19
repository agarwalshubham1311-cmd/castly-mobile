package com.castly.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public record AdminRequest(UUID requestId, String ip, String agent) {
  public static AdminRequest from(HttpServletRequest request, AdminSettings settings) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(settings.auditKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String fingerprint =
          HexFormat.of()
              .formatHex(mac.doFinal(request.getRemoteAddr().getBytes(StandardCharsets.UTF_8)));
      String ua = request.getHeader("User-Agent");
      ua = ua == null ? "" : ua.toLowerCase(Locale.ROOT);
      String agent =
          ua.contains("firefox")
              ? "firefox"
              : ua.contains("chrome") || ua.contains("chromium")
                  ? "chromium"
                  : ua.contains("safari") ? "safari" : "other";
      Object id = request.getAttribute("castly.admin.request");
      return new AdminRequest(
          id instanceof UUID uuid ? uuid : UUID.randomUUID(), fingerprint, agent);
    } catch (Exception e) {
      throw new IllegalStateException("Admin metadata unavailable");
    }
  }
}
