package com.castly.engagement;

import static com.castly.api.MarketplaceController.actor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/engagement")
public class EngagementController {
  private final EngagementService service;
  private final Phase6Settings flags;

  public EngagementController(EngagementService service, Phase6Settings flags) {
    this.service = service;
    this.flags = flags;
  }

  @GetMapping("/configuration")
  public Map<String, Boolean> configuration() {
    return flags.configuration();
  }

  @GetMapping("/preferences")
  public Map<String, Object> preferences(@AuthenticationPrincipal Jwt jwt) {
    return service.preferences(actor(jwt));
  }

  @PutMapping("/preferences")
  public Map<String, Object> preferences(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EngagementService.Preferences body) {
    return service.preferences(actor(jwt), body);
  }

  public record Device(
      @NotNull UUID deviceKey,
      @NotBlank @Size(max = 240) String token,
      @NotBlank String platform) {}

  @PostMapping("/devices")
  public Map<String, Object> register(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Device d) {
    return service.register(actor(jwt), d.deviceKey(), d.token(), d.platform());
  }

  @DeleteMapping("/devices/{key}")
  public void removeDevice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID key) {
    service.unregister(actor(jwt), key);
  }

  @PostMapping("/email/verify")
  public Map<String, Object> verify(@AuthenticationPrincipal Jwt jwt) {
    return service.contact(actor(jwt), jwt.getTokenValue());
  }

  public record Search(
      @NotBlank @Size(max = 80) String label,
      @NotNull @Valid EngagementService.Filters filters,
      boolean alerts) {}

  @GetMapping("/saved-searches")
  public List<Map<String, Object>> saved(@AuthenticationPrincipal Jwt jwt) {
    return service.saved(actor(jwt));
  }

  @PostMapping("/saved-searches")
  public Map<String, Object> save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Search s) {
    return service.save(actor(jwt), null, s.label(), s.filters(), s.alerts());
  }

  @PutMapping("/saved-searches/{id}")
  public Map<String, Object> save(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Search s) {
    return service.save(actor(jwt), id, s.label(), s.filters(), s.alerts());
  }

  @PostMapping("/saved-searches/{id}/results")
  public List<Map<String, Object>> results(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.results(actor(jwt), id);
  }

  @DeleteMapping("/saved-searches/{id}")
  public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.remove(actor(jwt), id);
  }

  @PostMapping("/open/{id}")
  public Map<String, Object> open(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.open(actor(jwt), id);
  }

  @GetMapping("/export")
  public Map<String, Object> export(@AuthenticationPrincipal Jwt jwt) {
    return service.export(actor(jwt));
  }

  @DeleteMapping("/data")
  public void delete(@AuthenticationPrincipal Jwt jwt) {
    service.delete(actor(jwt));
  }

  @GetMapping("/admin/analytics")
  public Map<String, Object> analytics(@AuthenticationPrincipal Jwt jwt) {
    return service.analytics(actor(jwt));
  }
}
