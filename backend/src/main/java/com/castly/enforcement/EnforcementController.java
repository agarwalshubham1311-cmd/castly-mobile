package com.castly.enforcement;

import static com.castly.api.MarketplaceController.actor;

import com.castly.admin.AdminService;
import com.castly.api.ActorDatabase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Validated
public class EnforcementController {
  private final AccountEnforcement enforcement;
  private final ActorDatabase data;
  private final AdminService admin;

  public EnforcementController(
      AccountEnforcement enforcement, ActorDatabase data, AdminService admin) {
    this.enforcement = enforcement;
    this.data = data;
    this.admin = admin;
  }

  public record Command(
      @NotNull UUID request_key,
      @NotBlank
          @Pattern(
              regexp =
                  "ENFORCE|REINSTATE|PROPOSE|APPROVE|ESCALATE|INVESTIGATE|APPEAL_SUBMIT|APPEAL_REVIEW|APPEAL_DECIDE|APPEAL_WITHDRAW|APPEAL_REOPEN_REQUEST|APPEAL_REOPEN")
          String operation,
      @NotNull @Size(max = 20) Map<String, Object> payload) {}

  @GetMapping("/account/access")
  public Map<String, Object> mine(@AuthenticationPrincipal Jwt jwt) {
    return data.as(actor(jwt), db -> enforcement.status(db, actor(jwt), false));
  }

  @PostMapping("/account/appeals")
  public Map<String, Object> appeal(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Command body) {
    if (!Set.of("APPEAL_SUBMIT", "APPEAL_WITHDRAW", "APPEAL_REOPEN_REQUEST")
        .contains(body.operation())) throw new IllegalArgumentException("Invalid appeal action");
    return data.as(
        actor(jwt),
        db ->
            enforcement.command(
                db, actor(jwt), body.request_key(), body.operation(), body.payload()));
  }

  @PostMapping("/subscriptions/purchase-eligibility")
  public Map<String, Boolean> purchase(@AuthenticationPrincipal Jwt jwt) {
    enforcement.purchase(actor(jwt));
    return Map.of("allowed", true);
  }

  @GetMapping("/admin/users/{id}/access")
  public Map<String, Object> detail(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @PathVariable UUID id,
      HttpServletRequest request) {
    enforcement.requireEnabled();
    return admin.authorized(
        jwt,
        session,
        request,
        "accounts.read",
        "Review account access",
        false,
        db -> enforcement.status(db, id, true));
  }

  @PostMapping("/admin/users/{id}/access")
  public Map<String, Object> command(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @PathVariable UUID id,
      @Valid @RequestBody Command body,
      HttpServletRequest request) {
    enforcement.requireEnabled();
    String permission =
        switch (body.operation()) {
          case "ESCALATE" -> "accounts.escalate";
          case "PROPOSE", "APPROVE" -> "accounts.permanent";
          case "APPEAL_REVIEW", "APPEAL_DECIDE", "APPEAL_REOPEN" -> "accounts.appeals";
          case "ENFORCE", "REINSTATE", "INVESTIGATE" -> "accounts.enforce";
          default -> throw new IllegalArgumentException("Invalid administrative action");
        };
    return admin.authorized(
        jwt,
        session,
        request,
        permission,
        "Account enforcement review",
        true,
        db -> enforcement.command(db, id, body.request_key(), body.operation(), body.payload()));
  }

  @GetMapping("/admin/account-queues/{kind}")
  public List<Map<String, Object>> queue(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @PathVariable @Pattern(regexp = "appeals|approvals|overdue") String kind,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID after,
      @RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit,
      HttpServletRequest request) {
    enforcement.requireEnabled();
    return admin.authorized(
        jwt,
        session,
        request,
        !kind.equals("approvals") ? "accounts.appeals" : "accounts.permanent",
        "Review account queue",
        false,
        db -> enforcement.queue(db, kind, after, limit));
  }
}
