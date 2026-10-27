package com.castly.api;

import static com.castly.api.ActorDatabase.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class MarketplaceController {
  private final ActorDatabase data;

  public MarketplaceController(ActorDatabase data) {
    this.data = data;
  }

  public static UUID actor(Jwt jwt) {
    return jwt == null ? null : UUID.fromString(jwt.getSubject());
  }

  private Map<String, Object> account(JdbcClient db, UUID me) {
    return one(db.sql("select * from public.users where id=:me").param("me", me));
  }

  @GetMapping("/account")
  public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt) {
    return data.as(actor(jwt), db -> account(db, actor(jwt)));
  }

  @PostMapping("/account/onboarding")
  public Map<String, Object> onboard(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Onboard body) {
    return data.as(
        actor(jwt),
        db -> {
          var row =
              one(
                  db.sql("select * from public.users where id=:me for update")
                      .param("me", actor(jwt)));
          if (row.get("primary_role") != null
              && !row.get("primary_role").equals(body.selectedRole()))
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Primary role cannot be changed");
          return one(
              db.sql(
                      "update public.users set primary_role=cast(:role as"
                          + " public.account_role),display_name=:name where id=:me returning *")
                  .param("role", body.selectedRole())
                  .param("name", body.accountName().trim())
                  .param("me", actor(jwt)));
        });
  }

  @PatchMapping("/account")
  public Map<String, Object> rename(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Rename body) {
    return data.as(
        actor(jwt),
        db ->
            one(
                db.sql("update public.users set display_name=:name where id=:me returning *")
                    .param("name", body.displayName().trim())
                    .param("me", actor(jwt))));
  }

  @GetMapping("/account/entitlements")
  public List<Map<String, Object>> entitlements(@AuthenticationPrincipal Jwt jwt) {
    return data.as(
        actor(jwt),
        db ->
            rows(
                db.sql("select * from public.subscription_entitlements where user_id=:me")
                    .param("me", actor(jwt))));
  }

  @GetMapping("/categories")
  public List<Map<String, Object>> categories(@AuthenticationPrincipal Jwt jwt) {
    return data.as(actor(jwt), db -> rows(db.sql("select * from public.categories order by name")));
  }

  @GetMapping("/talents")
  public List<Map<String, Object>> discovery(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "") @Size(max = 100) String query,
      @RequestParam(defaultValue = "") @Size(max = 100) String category,
      @RequestParam(defaultValue = "") @Size(max = 100) String city,
      @RequestParam(defaultValue = "0") @Min(0) int minRate,
      @RequestParam(defaultValue = "10000000") @Max(10000000) @Min(0) int maxRate,
      @RequestParam(defaultValue = "false") boolean available,
      @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset) {
    if (minRate > maxRate)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid rate range");
    return data.as(
        actor(jwt),
        db ->
            rows(
                db.sql(
                        "select * from public.talent_profiles where published and (:query='' or"
                            + " position(lower(:query) in lower(display_name||' '||city||'"
                            + " '||bio||' '||array_to_string(skills,' ')))>0) and (:category='' or"
                            + " category_id=:category) and (:city='' or lower(city)=lower(:city))"
                            + " and day_rate between :min and :max and (not :available or"
                            + " available) order by created_at desc,id limit :limit offset :offset")
                    .param("query", query.trim())
                    .param("category", category)
                    .param("city", city)
                    .param("min", minRate)
                    .param("max", maxRate)
                    .param("available", available)
                    .param("limit", limit)
                    .param("offset", offset)));
  }

  @GetMapping("/talents/me")
  public Map<String, Object> own(@AuthenticationPrincipal Jwt jwt) {
    if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return data.as(
        actor(jwt),
        db -> {
          var found =
              rows(
                  db.sql("select * from public.talent_profiles where id=:me")
                      .param("me", actor(jwt)));
          if (found.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Talent profile not found");
          return found.getFirst();
        });
  }

  @GetMapping("/talents/{id}")
  public Map<String, Object> profile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return data.as(
        actor(jwt),
        db ->
            one(
                db.sql("select * from public.talent_profiles where id=:id and published")
                    .param("id", id)));
  }

  @PostMapping("/talents/me")
  public Map<String, Object> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Talent body) {
    return save(jwt, body, true);
  }

  @PutMapping("/talents/me")
  public Map<String, Object> update(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Talent body) {
    return save(jwt, body, false);
  }

  private Map<String, Object> save(Jwt jwt, Requests.Talent body, boolean create) {
    UUID me = actor(jwt);
    if (body.portraitPath() != null && !body.portraitPath().startsWith(me + "/"))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Portrait must belong to your account");
    return data.as(
        me,
        db -> {
          if (!"talent".equals(account(db, me).get("primary_role")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Talent account required");
          String sql =
              create
                  ? "insert into"
                        + " public.talent_profiles(id,display_name,category_id,city,day_rate,bio,skills,available,published,portrait_path)"
                        + " values(:me,:name,:category,:city,:rate,:bio,string_to_array(:skills,chr(31)),:available,:published,:portrait)"
                        + " returning *"
                  : "update public.talent_profiles set"
                        + " display_name=:name,category_id=:category,city=:city,day_rate=:rate,bio=:bio,skills=string_to_array(:skills,chr(31)),available=:available,published=:published,portrait_path=:portrait"
                        + " where id=:me returning *";
          if (body.skills().stream().anyMatch(s -> s.contains("\u001f")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid skill");
          return one(
              db.sql(sql)
                  .param("me", me)
                  .param("name", body.displayName().trim())
                  .param("category", body.categoryId())
                  .param("city", body.city().trim())
                  .param("rate", body.dayRate())
                  .param("bio", body.bio())
                  .param("skills", String.join("\u001f", body.skills()))
                  .param("available", body.available())
                  .param("published", body.published())
                  .param("portrait", body.portraitPath(), java.sql.Types.VARCHAR));
        });
  }

  @GetMapping("/portfolio")
  public List<Map<String, Object>> portfolio(@AuthenticationPrincipal Jwt jwt) {
    return data.as(
        actor(jwt),
        db ->
            rows(
                db.sql(
                    "select a.* from public.portfolio_assets a join public.talent_profiles t on"
                        + " t.id=a.talent_id where t.published order by a.position,a.id limit"
                        + " 1000")));
  }

  @GetMapping("/talents/{id}/portfolio")
  public List<Map<String, Object>> portfolio(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return data.as(
        actor(jwt),
        db ->
            rows(
                db.sql(
                        "select * from public.portfolio_assets where talent_id=:id order by"
                            + " position,id")
                    .param("id", id)));
  }

  @PostMapping("/talents/me/portfolio")
  public Map<String, Object> add(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Portfolio body) {
    UUID me = actor(jwt);
    if (!body.path().startsWith(me + "/"))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Portfolio path must belong to your account");
    return data.as(
        me,
        db ->
            one(
                db.sql(
                        "insert into public.portfolio_assets(talent_id,path,label,position)"
                            + " values(:me,:path,:label,:position) returning *")
                    .param("me", me)
                    .param("path", body.path())
                    .param("label", body.label().trim())
                    .param("position", body.position())));
  }

  @PatchMapping("/portfolio/{id}")
  public Map<String, Object> edit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Requests.EditPortfolio body) {
    return data.as(
        actor(jwt),
        db ->
            one(
                db.sql(
                        "update public.portfolio_assets set label=:label,position=:position where"
                            + " id=:id and talent_id=:me returning *")
                    .param("label", body.label().trim())
                    .param("position", body.position())
                    .param("id", id)
                    .param("me", actor(jwt))));
  }

  @DeleteMapping("/portfolio/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    data.as(
        actor(jwt),
        db -> {
          one(
              db.sql(
                      "delete from public.portfolio_assets where id=:id and talent_id=:me returning"
                          + " *")
                  .param("id", id)
                  .param("me", actor(jwt)));
          return null;
        });
  }

  @GetMapping("/favourites")
  public List<Map<String, Object>> favourites(@AuthenticationPrincipal Jwt jwt) {
    return data.as(
        actor(jwt),
        db ->
            rows(
                db.sql("select * from public.saved_talent where user_id=:me order by created_at")
                    .param("me", actor(jwt))));
  }

  @PutMapping("/favourites/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void favourite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    data.as(
        actor(jwt),
        db -> {
          db.sql(
                  "insert into public.saved_talent(user_id,talent_id) values(:me,:id) on conflict"
                      + " do nothing")
              .param("me", actor(jwt))
              .param("id", id)
              .update();
          return null;
        });
  }

  @DeleteMapping("/favourites/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unfavourite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    data.as(
        actor(jwt),
        db -> {
          db.sql("delete from public.saved_talent where user_id=:me and talent_id=:id")
              .param("me", actor(jwt))
              .param("id", id)
              .update();
          return null;
        });
  }
}
