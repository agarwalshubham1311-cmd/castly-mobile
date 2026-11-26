package com.castly.admin;

import static com.castly.api.ActorDatabase.*;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Minimized, trigger-maintained operational metadata, never account documents or billing secrets.
 */
@Service
public class AdminUsersService {
  @org.springframework.beans.factory.annotation.Autowired
  private com.castly.enforcement.AccountEnforcement enforcement;

  private final boolean enabled;

  public AdminUsersService(@Value("${castly.admin.users-enabled:false}") boolean enabled) {
    this.enabled = enabled;
  }

  public void requireEnabled() {
    if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  static final String VERIFIED =
      "case when v.status='VERIFIED' and v.expires_at<=statement_timestamp() then 'EXPIRED' when"
          + " v.status='VERIFIED' and not coalesce(b.verified_until>statement_timestamp() and"
          + " b.portfolio_reviewed and b.facts_digest=t.facts_digest,false) then 'REVOKED' else"
          + " coalesce(v.status,'NOT_SUBMITTED') end";
  static final String DIRECTORY =
      " from admin_private.user_directory u left join admin_private.talent_summaries t on"
          + " t.user_id=u.user_id left join admin_private.verification_summaries v on"
          + " v.user_id=u.user_id left join admin_private.badge_summaries b on b.user_id=u.user_id"
          + " ";

  public record Page(List<Map<String, Object>> items, UUID nextAfter, int limit) {}

  public Page directory(
      JdbcClient db,
      UUID after,
      int limit,
      String query,
      String role,
      String verification,
      String state,
      LocalDate from,
      LocalDate until) {
    if (from != null && until != null && from.isAfter(until))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid creation date range");
    String q = query.trim();
    if (!q.isEmpty() && q.length() < 2)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Enter at least two prefix characters");
    // LIKE metacharacters are escaped; input is always bound, never interpolated into SQL.
    String prefix = q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    String where = " where u.user_id>:after";
    var parameters = new HashMap<String, Object>();
    parameters.put("after", after);
    parameters.put("n", limit + 1);
    if (!q.isEmpty()) {
      where += " and lower(u.display_name) like lower(:q) escape '!'";
      parameters.put("q", prefix);
    }
    if (!state.isEmpty()) {
      where +=
          " and ("
              + (state.equals("ACTIVE")
                  ? enforcement.stateField("u.user_id") + "=:state"
                  : enforcement.stateFilter())
              + ")";
      parameters.put("state", state);
    }
    if (!role.isEmpty()) {
      where += " and u.primary_role=:role";
      parameters.put("role", role);
    }
    if (!verification.isEmpty()) {
      where += " and (" + VERIFIED + ")=:verification";
      parameters.put("verification", verification);
    }
    if (from != null) {
      where += " and u.created_at>=:from";
      parameters.put("from", from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
    }
    if (until != null) {
      where += " and u.created_at<:until";
      parameters.put("until", until.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
    }
    var found =
        rows(
            db.sql(
                    "select"
                        + " u.user_id,u.display_name,u.primary_role,u.created_at,u.updated_at,t.category_id,t.city,t.published,"
                        + VERIFIED
                        + " as verification_status,"
                        + enforcement.stateField("u.user_id")
                        + " as enforcement_state"
                        + DIRECTORY
                        + where
                        + " order by u.user_id limit :n")
                .params(parameters));
    boolean more = found.size() > limit;
    var items = new ArrayList<>(found.subList(0, Math.min(limit, found.size())));
    return new Page(
        items, more ? UUID.fromString(items.getLast().get("user_id").toString()) : null, limit);
  }

  public Map<String, Object> detail(JdbcClient db, UUID user) {
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "account",
        one(
            db.sql(
                    "select u.user_id,u.display_name,u.primary_role,u.created_at,u.updated_at,"
                        + VERIFIED
                        + " as verification_status,"
                        + enforcement.stateField("u.user_id")
                        + " as enforcement_state"
                        + DIRECTORY
                        + " where u.user_id=:u")
                .param("u", user)));
    result.put(
        "talent",
        rows(
            db.sql(
                    "select user_id,display_name,category_id,city,day_rate,published,available from"
                        + " admin_private.talent_summaries where user_id=:u")
                .param("u", user)));
    result.put(
        "subscriptions",
        rows(
            db.sql(
                    "select entitlement,source,starts_at,expires_at,revoked_at,(revoked_at is null"
                        + " and starts_at<=statement_timestamp() and (expires_at is null or"
                        + " expires_at>statement_timestamp())) as active from"
                        + " admin_private.grant_summaries where user_id=:u order by id limit 50")
                .param("u", user)));
    result.put(
        "history",
        rows(
            db.sql(
                    "select id,action,occurred_at from admin_private.user_history where user_id=:u"
                        + " order by id desc limit 50")
                .param("u", user)));
    boolean auditAllowed =
        db.sql("select private.admin_permission('audit.read')").query(Boolean.class).single();
    result.put(
        "security_activity",
        auditAllowed
            ? Map.of(
                "events",
                rows(
                    db.sql(
                            "select action,outcome,occurred_at from admin_private.audit where"
                                + " actor_id=:u and outcome='denied' order by id desc limit 50")
                        .param("u", user)),
                "note",
                "Recorded privileged-access denials, not a complete identity-provider sign-in"
                    + " history or fraud assessment.")
            : null);
    result.put(
        "history_note",
        "Recorded since installation of the reviewed M7.2 migration; no earlier events are"
            + " inferred.");
    return result;
  }

  public Page bookings(JdbcClient db, UUID user, UUID after, int limit) {
    var found =
        rows(
            db.sql(
                    "select * from (select"
                        + " id,client_id,talent_id,project_type,proposed_date,status,created_at"
                        + " from admin_private.booking_summaries where client_id=:u and id>:a union"
                        + " all select"
                        + " id,client_id,talent_id,project_type,proposed_date,status,created_at"
                        + " from admin_private.booking_summaries where talent_id=:u and id>:a) s"
                        + " order by id limit :n")
                .param("u", user)
                .param("a", after)
                .param("n", limit + 1));
    boolean more = found.size() > limit;
    var items = new ArrayList<>(found.subList(0, Math.min(limit, found.size())));
    return new Page(
        items, more ? UUID.fromString(items.getLast().get("id").toString()) : null, limit);
  }
}
