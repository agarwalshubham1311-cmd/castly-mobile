package com.castly.admin;

import com.castly.trust.VerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users/verifications")
@Validated
public class AdminUserVerificationController {
  private final AdminService admin;
  private final AdminUsersService users;
  private final VerificationService verification;
  private final PortfolioPreviewGateway previews;

  public AdminUserVerificationController(
      AdminService admin,
      AdminUsersService users,
      VerificationService verification,
      PortfolioPreviewGateway previews) {
    this.admin = admin;
    this.users = users;
    this.verification = verification;
    this.previews = previews;
  }

  public record Decision(
      @Min(1) int expectedRevision,
      @NotBlank @Pattern(regexp = "PENDING|UNDER_REVIEW|VERIFIED|REJECTED|REVOKED|EXPIRED")
          String expectedStatus,
      @NotBlank @Pattern(regexp = "UNDER_REVIEW|VERIFIED|REJECTED|REVOKED") String decision,
      @NotBlank @Size(min = 3, max = 500) String reason) {}

  @GetMapping
  public List<Map<String, Object>> queue(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID after,
      @RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit,
      HttpServletRequest request) {
    users.requireEnabled();
    return admin.authorized(
        jwt,
        session,
        request,
        "users.verify",
        "Review verification queue",
        false,
        db -> verification.adminQueue(UUID.fromString(jwt.getSubject()), after, limit));
  }

  @GetMapping("/{id}")
  public Map<String, Object> detail(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @PathVariable UUID id,
      HttpServletRequest request) {
    users.requireEnabled();
    return admin.authorized(
        jwt,
        session,
        request,
        "users.verify",
        "Review verification evidence",
        false,
        db -> verification.adminDetail(UUID.fromString(jwt.getSubject()), id));
  }

  @PostMapping("/{id}/evidence/{evidence}/preview")
  public Map<String, Object> preview(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @PathVariable UUID id,
      @PathVariable UUID evidence,
      HttpServletRequest request) {
    users.requireEnabled();
    return admin.authorized(
        jwt,
        session,
        request,
        "users.verify",
        "Prepare consented portfolio preview",
        true,
        db -> {
          com.castly.trust.TrustAccess.reviewer(db, "verification");
          var application =
              com.castly.api.ActorDatabase.one(
                  db.sql(
                          "select id,user_id,submitted_at from public.verification_requests where"
                              + " id=:id for share")
                      .param("id", id));
          var asset =
              verification.evidence(UUID.fromString(jwt.getSubject()), id).stream()
                  .filter(
                      e ->
                          evidence.toString().equals(e.get("id").toString())
                              && e.get("portfolio_asset_id") != null)
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new org.springframework.web.server.ResponseStatusException(
                              org.springframework.http.HttpStatus.NOT_FOUND,
                              "Current review evidence unavailable"));
          var result =
              previews.sign(
                  jwt.getTokenValue(),
                  asset.get("portfolio_path").toString(),
                  java.time.Instant.parse(application.get("submitted_at").toString())
                      .plus(java.time.Duration.ofDays(30)));
          com.castly.trust.TrustAccess.reviewer(db, "verification");
          boolean current =
              verification.evidence(UUID.fromString(jwt.getSubject()), id).stream()
                  .anyMatch(
                      e ->
                          evidence.toString().equals(e.get("id").toString())
                              && e.get("portfolio_asset_id") != null
                              && asset.get("portfolio_path").equals(e.get("portfolio_path")));
          if (!current)
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Current review evidence unavailable");
          com.castly.trust.TrustAccess.audit(
              db,
              UUID.fromString(jwt.getSubject()),
              UUID.fromString(application.get("user_id").toString()),
              "verification",
              "portfolio.preview_issued",
              id,
              "Short-lived consented portfolio preview issued");
          return result;
        });
  }

  @PostMapping("/{id}/decision")
  public Map<String, Object> decide(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @PathVariable UUID id,
      @Valid @RequestBody Decision body,
      HttpServletRequest request) {
    users.requireEnabled();
    return admin.authorized(
        jwt,
        session,
        request,
        "users.verify",
        body.reason(),
        true,
        db ->
            verification.decideExpected(
                UUID.fromString(jwt.getSubject()),
                id,
                body.expectedRevision(),
                body.expectedStatus(),
                body.decision(),
                body.reason()));
  }
}
