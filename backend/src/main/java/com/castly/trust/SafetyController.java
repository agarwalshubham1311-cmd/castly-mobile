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
public class SafetyController {
  public record Report(
      @Pattern(regexp = "user|portfolio|message|review") @NotBlank String targetKind,
      @NotNull UUID targetId,
      @Pattern(regexp = "harassment|spam|impersonation|fraud|inappropriate_content|other") @NotBlank
          String category,
      @NotBlank @Size(min = 10, max = 2000) String description) {}

  public record Decision(
      @Pattern(regexp = "dismissed|warning|review_hidden") @NotBlank String decision,
      @NotBlank @Size(min = 3, max = 1000) String reason) {}

  public record Appeal(@NotBlank @Size(min = 10, max = 2000) String statement) {}

  public record AppealDecision(
      @NotBlank @Pattern(regexp = "UPHELD|OVERTURNED|ESCALATED") String status,
      @NotBlank @Size(min = 3, max = 1000) String reason) {}

  private final SafetyService service;

  public SafetyController(SafetyService service) {
    this.service = service;
  }

  @GetMapping("/blocks")
  public List<Map<String, Object>> blocks(@AuthenticationPrincipal Jwt jwt) {
    return service.blocks(actor(jwt));
  }

  @PutMapping("/blocks/{id}")
  public Map<String, Object> block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.block(actor(jwt), id, true);
  }

  @DeleteMapping("/blocks/{id}")
  public Map<String, Object> unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.block(actor(jwt), id, false);
  }

  @PostMapping("/reports")
  public Map<String, Object> report(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Report body) {
    return service.report(
        actor(jwt), body.targetKind(), body.targetId(), body.category(), body.description());
  }

  @GetMapping("/reports")
  public List<Map<String, Object>> reports(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return service.reports(actor(jwt), offset);
  }

  @GetMapping("/safety/actions")
  public List<Map<String, Object>> actions(@AuthenticationPrincipal Jwt jwt) {
    return service.actions(actor(jwt));
  }

  @GetMapping("/admin/moderation")
  public List<Map<String, Object>> queue(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return service.queue(actor(jwt), offset);
  }

  @PostMapping("/admin/moderation/{id}/decision")
  public Map<String, Object> decide(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Decision body) {
    return service.decide(actor(jwt), id, body.decision(), body.reason());
  }

  @PostMapping("/safety/actions/{id}/appeal")
  public Map<String, Object> appeal(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Appeal body) {
    return service.appeal(actor(jwt), id, body.statement());
  }

  @GetMapping("/safety/appeals")
  public List<Map<String, Object>> appeals(@AuthenticationPrincipal Jwt jwt) {
    return service.appeals(actor(jwt), false);
  }

  @GetMapping("/admin/moderation/appeals")
  public List<Map<String, Object>> appealQueue(@AuthenticationPrincipal Jwt jwt) {
    return service.appeals(actor(jwt), true);
  }

  @PostMapping("/admin/moderation/appeals/{id}/decision")
  public Map<String, Object> resolve(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody AppealDecision body) {
    return service.resolveAppeal(actor(jwt), id, body.status(), body.reason());
  }
}
