package com.castly.subscription;

import static com.castly.api.MarketplaceController.actor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PromotionController {
  public record Claim(@NotBlank @Size(max = 2048) String postUrl) {}

  public record Review(@NotBlank @Size(min = 3, max = 1000) String reason) {}

  private final PromotionService service;

  public PromotionController(PromotionService service) {
    this.service = service;
  }

  @PostMapping("/subscriptions/promotions/claims")
  public Map<String, Object> submit(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Claim body) {
    return service.submit(actor(jwt), body.postUrl());
  }

  @GetMapping("/subscriptions/promotions/me")
  public List<Map<String, Object>> own(@AuthenticationPrincipal Jwt jwt) {
    return service.own(actor(jwt));
  }

  @GetMapping("/admin/subscriptions/promotions")
  public List<Map<String, Object>> queue(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return service.queue(actor(jwt), limit, offset);
  }

  @PostMapping("/admin/subscriptions/promotions/{id}/approve")
  public Map<String, Object> approve(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Review body) {
    return service.review(actor(jwt), id, true, body.reason());
  }

  @PostMapping("/admin/subscriptions/promotions/{id}/reject")
  public Map<String, Object> reject(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Review body) {
    return service.review(actor(jwt), id, false, body.reason());
  }
}
