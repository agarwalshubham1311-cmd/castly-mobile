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

  public AdminUserVerificationController(
      AdminService admin, AdminUsersService users, VerificationService verification) {
    this.admin = admin;
    this.users = users;
    this.verification = verification;
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
