package com.castly.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
@Validated
public class AdminUsersController {
  private final AdminService admin;
  private final AdminUsersService users;

  public AdminUsersController(AdminService admin, AdminUsersService users) {
    this.admin = admin;
    this.users = users;
  }

  @GetMapping
  public AdminUsersService.Page directory(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID after,
      @RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit,
      @RequestParam(defaultValue = "") @Size(max = 80) String q,
      @RequestParam(defaultValue = "") @Pattern(regexp = "|client|talent") String role,
      @RequestParam(defaultValue = "")
          @Pattern(regexp = "|NOT_SUBMITTED|PENDING|UNDER_REVIEW|VERIFIED|REJECTED|EXPIRED|REVOKED")
          String verification,
      @RequestParam(defaultValue = "")
          @Pattern(regexp = "|ACTIVE|RESTRICTED|TEMPORARILY_SUSPENDED|PERMANENTLY_SUSPENDED")
          String state,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate until,
      HttpServletRequest request) {
    users.requireEnabled();
    return admin.authorized(
        jwt,
        session,
        request,
        "users.read",
        "Review user directory",
        false,
        db -> users.directory(db, after, limit, q, role, verification, state, from, until));
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
        "users.read",
        "Review account summary",
        false,
        db -> users.detail(db, id));
  }

  @GetMapping("/{id}/bookings")
  public AdminUsersService.Page bookings(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID after,
      @RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit,
      HttpServletRequest request) {
    users.requireEnabled();
    return admin.authorized(
        jwt,
        session,
        request,
        "bookings.read",
        "Review account booking metadata",
        false,
        db -> users.bookings(db, id, after, limit));
  }
}
