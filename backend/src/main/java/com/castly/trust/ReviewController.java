package com.castly.trust;

import static com.castly.api.MarketplaceController.actor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {
  public record Review(@Min(1) @Max(5) int rating, @NotNull @Size(max = 2000) String body) {}

  public record Moderation(boolean hidden, @NotBlank @Size(min = 3, max = 1000) String reason) {}

  private final ReviewService service;

  public ReviewController(ReviewService service) {
    this.service = service;
  }

  @GetMapping("/bookings/{id}/completion")
  public Map<String, Object> completion(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.completion(actor(jwt), id, false);
  }

  @PostMapping("/bookings/{id}/completion")
  public Map<String, Object> confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.completion(actor(jwt), id, true);
  }

  @PostMapping("/bookings/{id}/reviews")
  public Map<String, Object> submit(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Review body) {
    return service.submit(actor(jwt), id, body.rating(), body.body());
  }

  @GetMapping("/bookings/{id}/reviews/mine")
  public List<Map<String, Object>> own(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.own(actor(jwt), id);
  }

  @PatchMapping("/reviews/{id}")
  public Map<String, Object> edit(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Review body) {
    return service.edit(actor(jwt), id, body.rating(), body.body());
  }

  @GetMapping("/talents/{id}/reviews")
  public Map<String, Object> list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return service.publicReviews(actor(jwt), id, limit, offset);
  }

  @PostMapping("/admin/reviews/{id}/moderation")
  public Map<String, Object> moderate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Moderation body) {
    return service.moderate(actor(jwt), id, body.hidden(), body.reason());
  }
}
