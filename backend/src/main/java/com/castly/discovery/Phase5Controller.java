package com.castly.discovery;

import static com.castly.api.MarketplaceController.actor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class Phase5Controller {
  private final Phase5Service service;
  private final CampaignParser parser;
  public Phase5Controller(Phase5Service service, CampaignParser parser) {
    this.service = service; this.parser = parser;
  }

  public record Description(@NotBlank @Size(max=1000) String description) {}
  public record Preference(@Size(max=100) String region, boolean shareRegion) {}
  public record Day(@NotNull LocalDate day, boolean available) {}
  public record Brief(@NotNull @Valid Campaign constraints, @NotNull UUID talentId) {}
  public record Comparison(@NotEmpty @Size(max=3) List<UUID> talentIds) {}

  @GetMapping("/discovery/configuration")
  public Map<String, Boolean> configuration() { return service.configuration(); }

  @DeleteMapping("/discovery/me/ai-cache")
  public void forget(@AuthenticationPrincipal Jwt jwt) { service.forget(actor(jwt)); }

  @GetMapping("/discovery/advanced")
  public List<Map<String,Object>> advanced(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue="") String category,
      @RequestParam(defaultValue="") String city,
      @RequestParam(defaultValue="") String region,
      @RequestParam(defaultValue="") List<String> skills,
      @RequestParam(name="min_rate",required=false) Integer minRate,
      @RequestParam(name="max_rate",required=false) Integer maxRate,
      @RequestParam(name="from",required=false) LocalDate from,
      @RequestParam(name="through",required=false) LocalDate through,
      @RequestParam(defaultValue="any") String availability,
      @RequestParam(defaultValue="name") String sort,
      @RequestParam(defaultValue="25") int limit,
      @RequestParam(defaultValue="0") int offset) {
    return service.search(actor(jwt), new Phase5Service.Search(category,city,region,skills,
        minRate,maxRate,from,through,availability,sort,limit,offset));
  }

  @PutMapping("/discovery/me/preferences")
  public Map<String,Object> preference(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Preference body) {
    return service.preferences(actor(jwt),body.region(),body.shareRegion());
  }
  @PutMapping("/discovery/me/availability")
  public Map<String,Object> availability(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Day body) {
    return service.availability(actor(jwt),body.day(),body.available());
  }
  @PostMapping("/matching/parse")
  public Map<String,Object> parse(@Valid @RequestBody Description body) { return service.parse(body.description(), parser); }
  @PostMapping("/matching/shortlist")
  public Map<String,Object> shortlist(@AuthenticationPrincipal Jwt jwt, @RequestBody Campaign body) {
    return service.shortlist(actor(jwt),body,false);
  }
  @PostMapping("/recommendations")
  public Map<String,Object> recommendations(@AuthenticationPrincipal Jwt jwt, @RequestBody Campaign body) {
    return service.shortlist(actor(jwt),body,true);
  }
  @PostMapping("/assistance/brief")
  public Map<String,Object> brief(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Brief body) {
    return service.brief(actor(jwt),body.constraints(),body.talentId());
  }
  @PostMapping("/assistance/profile")
  public Map<String,Object> profile(@AuthenticationPrincipal Jwt jwt) { return service.profile(actor(jwt)); }
  @PostMapping("/assistance/compare")
  public Map<String,Object> compare(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Comparison body) {
    return service.compare(actor(jwt),body.talentIds());
  }
}
