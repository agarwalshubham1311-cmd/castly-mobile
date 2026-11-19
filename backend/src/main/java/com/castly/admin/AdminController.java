package com.castly.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@Validated
public class AdminController {
  public record Reason(@NotBlank @Size(min = 3, max = 500) String reason) {}

  public record Change(
      @NotEmpty @Size(max = 6)
          List<
                  @Pattern(
                      regexp =
                          "SUPER_ADMIN|OPERATIONS_ADMIN|MODERATOR|SUPPORT_AGENT|FINANCE_ADMIN|ANALYST")
                  String>
              roles,
      @Pattern(regexp = "active|revoked") @NotNull String state,
      @Min(1) long expectedVersion,
      @NotBlank @Size(min = 3, max = 500) String reason) {}

  public record Invite(
      @NotNull UUID userId,
      @NotEmpty @Size(max = 5)
          List<
                  @Pattern(
                      regexp = "OPERATIONS_ADMIN|MODERATOR|SUPPORT_AGENT|FINANCE_ADMIN|ANALYST")
                  String>
              roles,
      @NotBlank @Size(min = 3, max = 500) String reason) {}

  public record Activation(
      @Pattern(regexp = "[A-Za-z0-9_-]{43}") @NotNull String token,
      @NotBlank @Size(min = 3, max = 500) String reason) {}

  private final AdminService service;
  private final AdminSettings settings;

  public AdminController(AdminService service, AdminSettings settings) {
    this.service = service;
    this.settings = settings;
  }

  private AdminRequest context(HttpServletRequest request) {
    return AdminRequest.from(request, settings);
  }

  @PostMapping("/sessions")
  public Map<String, Object> login(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    return service.login(service.authenticate(jwt, context(request)), context(request));
  }

  @GetMapping("/me")
  public Map<String, Object> me(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      HttpServletRequest request) {
    return service.me(service.authenticate(jwt, context(request)), session, context(request));
  }

  @GetMapping("/principals")
  public List<Map<String, Object>> principals(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID after,
      @RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit,
      HttpServletRequest request) {
    return service.principals(
        service.authenticate(jwt, context(request)), session, context(request), after, limit);
  }

  @PatchMapping("/principals/{id}")
  public Map<String, Object> change(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @PathVariable UUID id,
      @Valid @RequestBody Change body,
      HttpServletRequest request) {
    return service.change(
        service.authenticate(jwt, context(request)), session, context(request), id, body);
  }

  @PostMapping("/invitations")
  public Map<String, Object> invite(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @Valid @RequestBody Invite body,
      HttpServletRequest request) {
    return service.invite(
        service.authenticate(jwt, context(request)), session, context(request), body);
  }

  @PostMapping("/invitations/activate")
  public Map<String, Object> activate(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody Activation body,
      HttpServletRequest request) {
    return service.activate(
        service.authenticate(jwt, context(request)), context(request), body.token(), body.reason());
  }

  @GetMapping("/sessions")
  public List<Map<String, Object>> sessions(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID after,
      @RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit,
      HttpServletRequest request) {
    return service.sessions(
        service.authenticate(jwt, context(request)), session, context(request), after, limit);
  }

  @DeleteMapping("/sessions/{id}")
  public void revoke(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @PathVariable UUID id,
      @Valid @RequestBody Reason body,
      HttpServletRequest request) {
    service.revoke(
        service.authenticate(jwt, context(request)), session, context(request), id, body.reason());
  }

  @GetMapping("/audit")
  public List<Map<String, Object>> audit(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("X-Castly-Admin-Session") UUID session,
      @RequestParam(defaultValue = "9223372036854775807") @Min(1) long before,
      @RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit,
      HttpServletRequest request) {
    return service.audit(
        service.authenticate(jwt, context(request)), session, context(request), before, limit);
  }
}
