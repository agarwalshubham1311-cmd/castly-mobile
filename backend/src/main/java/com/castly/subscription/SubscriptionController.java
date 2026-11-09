package com.castly.subscription;

import static com.castly.api.MarketplaceController.actor;

import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {
  private final SubscriptionService service;

  public SubscriptionController(SubscriptionService service) {
    this.service = service;
  }

  @GetMapping("/plans")
  public List<Map<String, Object>> plans() {
    return service.plans();
  }

  @GetMapping("/me")
  public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
    return service.current(actor(jwt));
  }

  @GetMapping("/history")
  public List<Map<String, Object>> history(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return service.history(actor(jwt), limit, offset);
  }
}
