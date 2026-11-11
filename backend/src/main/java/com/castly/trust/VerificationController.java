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
public class VerificationController {
  public record Application(
      @NotBlank @Size(min = 20, max = 2000) String statement,
      @NotEmpty @Size(max = 10) List<@NotNull UUID> portfolioAssetIds,
      @NotBlank @Size(max = 80) String consentVersion) {}

  public record Decision(
      @NotBlank @Pattern(regexp = "UNDER_REVIEW|VERIFIED|REJECTED|REVOKED") String decision,
      @NotBlank @Size(min = 3, max = 1000) String reason) {}

  private final VerificationService service;
  private final TrustedContactService contact;

  public VerificationController(VerificationService service, TrustedContactService contact) {
    this.service = service;
    this.contact = contact;
  }

  @GetMapping("/verification/me")
  public Map<String, Object> own(@AuthenticationPrincipal Jwt jwt) {
    return service.own(actor(jwt));
  }

  @GetMapping("/verification/contact")
  public Map<String, Object> contact(@AuthenticationPrincipal Jwt jwt) {
    return contact.contact(jwt.getTokenValue(), actor(jwt));
  }

  @GetMapping("/verification/history")
  public List<Map<String, Object>> history(@AuthenticationPrincipal Jwt jwt) {
    return service.history(actor(jwt));
  }

  @PostMapping("/verification")
  public Map<String, Object> submit(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Application body) {
    return service.submit(
        actor(jwt), body.statement(), body.portfolioAssetIds(), body.consentVersion());
  }

  @GetMapping("/verification/{id}/evidence")
  public List<Map<String, Object>> evidence(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.evidence(actor(jwt), id);
  }

  @GetMapping("/admin/verification")
  public List<Map<String, Object>> queue(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return service.queue(actor(jwt), limit, offset);
  }

  @PostMapping("/admin/verification/{id}/decision")
  public Map<String, Object> decide(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Decision body) {
    return service.decide(actor(jwt), id, body.decision(), body.reason());
  }
}
