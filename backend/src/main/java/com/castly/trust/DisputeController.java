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
public class DisputeController {
  public record Filing(
      @NotNull UUID bookingId,
      @NotBlank @Size(min = 20, max = 4000) String statement,
      @NotBlank String consentVersion) {}

  public record Note(
      @NotBlank @Size(min = 10, max = 3000) String note, @NotBlank String consentVersion) {}

  public record Appeal(@NotBlank @Size(min = 20, max = 4000) String statement) {}

  public record Decision(
      @NotBlank @Pattern(regexp = "UNDER_REVIEW|RESOLVED|ESCALATED|FINAL_RESOLVED") String status,
      @NotBlank @Size(min = 3, max = 2000) String reason) {}

  public record Upload(
      @NotBlank @Size(max = 7000000) String imageBase64,
      @NotBlank @Size(max = 80) String consentVersion) {}

  private final DisputeService service;
  private final EvidenceService evidence;

  public DisputeController(DisputeService service, EvidenceService evidence) {
    this.service = service;
    this.evidence = evidence;
  }

  @PostMapping("/disputes")
  public Map<String, Object> file(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Filing body) {
    return service.create(actor(jwt), body.bookingId(), body.statement(), body.consentVersion());
  }

  @GetMapping("/disputes")
  public List<Map<String, Object>> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return service.list(actor(jwt), false, offset);
  }

  @GetMapping("/disputes/{id}")
  public Map<String, Object> details(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.details(actor(jwt), id);
  }

  @PostMapping("/disputes/{id}/notes")
  public Map<String, Object> note(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Note body) {
    return service.note(actor(jwt), id, body.note(), body.consentVersion());
  }

  @PostMapping("/disputes/{id}/appeal")
  public Map<String, Object> appeal(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Appeal body) {
    return service.appeal(actor(jwt), id, body.statement());
  }

  @GetMapping("/admin/disputes")
  public List<Map<String, Object>> queue(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return service.list(actor(jwt), true, offset);
  }

  @PostMapping("/admin/disputes/{id}/decision")
  public Map<String, Object> decide(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Decision body) {
    return service.decide(actor(jwt), id, body.status(), body.reason());
  }

  @GetMapping("/disputes/evidence/configuration")
  public Map<String, Object> configuration(@AuthenticationPrincipal Jwt jwt) {
    return evidence.configuration(actor(jwt));
  }

  @PostMapping("/disputes/{id}/images")
  public Map<String, Object> upload(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Upload body) {
    return evidence.upload(
        actor(jwt), jwt.getTokenValue(), id, body.consentVersion(), body.imageBase64());
  }

  @GetMapping("/disputes/{id}/evidence/{evidenceId}/url")
  public Map<String, Object> sign(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID evidenceId) {
    return evidence.signed(actor(jwt), jwt.getTokenValue(), id, evidenceId);
  }

  @DeleteMapping("/disputes/evidence/{id}")
  public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    evidence.remove(actor(jwt), jwt.getTokenValue(), id, false);
  }

  @PostMapping("/admin/disputes/evidence/cleanup")
  public Map<String, Object> cleanup(@AuthenticationPrincipal Jwt jwt) {
    return evidence.cleanup(actor(jwt), jwt.getTokenValue());
  }
}
