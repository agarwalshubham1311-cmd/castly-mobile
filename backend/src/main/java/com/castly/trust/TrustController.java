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

  public TrustController(TrustSignals signals) {
    this.signals = signals;
  }

  @GetMapping("/trust/me")
  public Map<String, Object> own(@AuthenticationPrincipal Jwt jwt) {
    return signals.own(actor(jwt));
  }

  @GetMapping("/admin/trust/{id}")
  public Map<String, Object> internal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return signals.internal(actor(jwt), id);
  }
}
