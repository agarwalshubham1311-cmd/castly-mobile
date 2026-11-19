package com.castly.trust;

import static com.castly.api.MarketplaceController.actor;

import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class TrustController {
  private final TrustSignals signals;

  private final com.castly.admin.AdminService admin;

  public TrustController(TrustSignals signals, com.castly.admin.AdminService admin) {
    this.admin = admin;
    this.signals = signals;
  }

  @GetMapping("/trust/me")
  public Map<String, Object> own(@AuthenticationPrincipal Jwt jwt) {
    return signals.own(actor(jwt));
  }

  @GetMapping("/admin/trust/{id}")
  public Map<String, Object> internal(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      jakarta.servlet.http.HttpServletRequest request) {
    return admin.delegated(
        jwt,
        request,
        "users.verify",
        "Reviewed existing privileged operation",
        false,
        () -> signals.internal(actor(jwt), id));
  }
}
