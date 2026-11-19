package com.castly.admin;

import com.castly.api.ActorDatabase;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {
  private final ActorDatabase data;
  private final AdminSettings settings;

  public AdminService(ActorDatabase data, AdminSettings settings) {
    this.data = data;
    this.settings = settings;
  }

  public AdminIdentity authenticate(
      org.springframework.security.oauth2.jwt.Jwt jwt, AdminRequest request) {
    try {
      return AdminIdentity.from(jwt);
    } catch (ResponseStatusException denied) {
      // The resource server has already verified the JWT; invalid MFA still gets an actor-bound
      // denial record.
      AdminIdentity failed =
          new AdminIdentity(
              UUID.fromString(jwt.getSubject()), new UUID(0, 0), java.time.Instant.EPOCH);
      rate(failed, "login");
      auditDenied(failed, request, "session.denied");
      throw denied;
    }
  }

  private static ResponseStatusException denied() {
    return new ResponseStatusException(
        HttpStatus.FORBIDDEN, "Administrator permission or session required");
  }

  private static void bind(
      JdbcClient db, AdminIdentity identity, UUID session, AdminRequest request, String reason) {
    db.sql(
            "select"
                + " set_config('request.admin.aal2','true',true),set_config('request.admin.mfa_at',:m,true),set_config('request.admin.auth_session',:s,true),set_config('request.admin.session',:a,true),set_config('request.admin.request_id',:r,true),set_config('request.admin.ip',:i,true),set_config('request.admin.agent',:u,true),set_config('request.admin.reason',:reason,true)")
        .param("m", identity.mfaAt().toString())
        .param("s", identity.authSession().toString())
        .param("a", session == null ? "" : session.toString())
        .param("r", request.requestId().toString())
        .param("i", request.ip())
        .param("u", request.agent())
        .param("reason", reason)
        .query()
        .singleRow();
    db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
        .param("key", "admin:" + identity.user())
        .query()
        .singleRow();
  }

  private void rate(AdminIdentity id, String bucket) {
    int attempts =
        data.as(
            id.user(),
            db ->
                db.sql(
                        "insert into"
                            + " admin_private.rate_limits(user_id,bucket,window_start,attempts)"
                            + " values(:u,:b,date_trunc('minute',now()),1) on"
                            + " conflict(user_id,bucket) do update set"
                            + " window_start=date_trunc('minute',now()),attempts=case when"
                            + " rate_limits.window_start=date_trunc('minute',now()) then"
                            + " rate_limits.attempts+1 else 1 end returning attempts")
                    .param("u", id.user())
                    .param("b", bucket)
                    .query(Integer.class)
                    .single());
    if (attempts > (bucket.equals("login") ? 5 : bucket.equals("write") ? 20 : 300))
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Administrative rate limit. Retry in a minute.");
  }

  public void auditDenied(AdminIdentity id, AdminRequest request, String action) {
    data.as(
        id.user(),
        db -> {
          bind(db, id, null, request, "Authorization or session rejected");
          db.sql(
                  "insert into"
                      + " admin_private.audit(actor_id,action,request_id,reason,outcome,ip_fingerprint,agent_family)"
                      + " values(:u,:a,:r,'Authorization or session rejected','denied',:i,:g)")
              .param("u", id.user())
              .param("a", action)
              .param("r", request.requestId())
              .param("i", request.ip())
              .param("g", request.agent())
              .update();
          return null;
        });
  }

  private <T> T run(
      AdminIdentity id,
      UUID session,
      AdminRequest request,
      String permission,
      String reason,
      boolean sensitive,
      Function<JdbcClient, T> operation) {
    if (!settings.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    try {
      rate(id, session == null ? "login" : sensitive ? "write" : "read");
      if (sensitive) id.recent();
      return data.as(
          id.user(),
          db -> {
            bind(db, id, session, request, reason);
            if (session != null) {
              boolean valid =
                  db.sql(
                          "select exists(select 1 from admin_private.sessions s join"
                              + " admin_private.authorizations a on a.user_id=s.user_id and"
                              + " a.version=s.principal_version where s.id=:s and s.user_id=:u and"
                              + " s.auth_session_id=:auth and s.revoked_at is null and"
                              + " s.expires_at>now() and a.state='active')")
                      .param("s", session)
                      .param("u", id.user())
                      .param("auth", id.authSession())
                      .query(Boolean.class)
                      .single();
              if (!valid) throw denied();
            }
            if (permission != null
                && !db.sql("select private.admin_permission(:p)")
                    .param("p", permission)
                    .query(Boolean.class)
                    .single()) throw denied();
            return operation.apply(db);
          });
    } catch (ResponseStatusException error) {
      auditDenied(
          id,
          request,
          error.getStatusCode().value() == 429
              ? "rate.denied"
              : session == null ? "session.denied" : "access.denied");
      throw error;
    } catch (org.springframework.dao.DataAccessException error) {
      auditDenied(id, request, "access.denied");
      throw error;
    }
  }

  private Map<String, Object> principal(JdbcClient db, UUID user) {
    var rows =
        ActorDatabase.rows(
            db.sql(
                    "select user_id,roles,state,version from admin_private.principals where"
                        + " user_id=:u")
                .param("u", user));
    if (rows.isEmpty() || !"active".equals(rows.getFirst().get("state"))) throw denied();
    var result = new LinkedHashMap<>(rows.getFirst());
    result.put(
        "permissions",
        db.sql(
                "select distinct permission from admin_private.role_permissions where role in"
                    + " (select unnest(roles) from admin_private.authorizations where user_id=:u)"
                    + " order by permission")
            .param("u", user)
            .query(String.class)
            .list());
    return result;
  }

  public Map<String, Object> login(AdminIdentity id, AdminRequest request) {
    return run(
        id,
        null,
        request,
        null,
        "MFA administrator session",
        true,
        db -> {
          var result = principal(db, id.user());
          int count =
              db.sql(
                      "select count(*) from admin_private.sessions where user_id=:u and"
                          + " created_at>now()-interval '10 minutes'")
                  .param("u", id.user())
                  .query(Integer.class)
                  .single();
          if (count >= 3)
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Unusual administrator session activity. Retry later.");
          var session =
              ActorDatabase.one(
                  db.sql(
                          "insert into"
                              + " admin_private.sessions(user_id,auth_session_id,principal_version,ip_fingerprint,agent_family)"
                              + " values(:u,:a,:v,:i,:g) returning id,expires_at")
                      .param("u", id.user())
                      .param("a", id.authSession())
                      .param("v", result.get("version"))
                      .param("i", request.ip())
                      .param("g", request.agent()));
          result.put("session", session);
          return result;
        });
  }

  public Map<String, Object> me(AdminIdentity id, UUID session, AdminRequest request) {
    return run(
        id,
        session,
        request,
        null,
        "Read own administrator session",
        false,
        db -> {
          var result = principal(db, id.user());
          result.put(
              "expires_at",
              db.sql("select expires_at::text from admin_private.sessions where id=:s")
                  .param("s", session)
                  .query(String.class)
                  .single());
          return result;
        });
  }

  public List<Map<String, Object>> principals(
      AdminIdentity id, UUID session, AdminRequest request, UUID after, int limit) {
    return run(
        id,
        session,
        request,
        "admins.manage",
        "Review administrative directory",
        false,
        db ->
            ActorDatabase.rows(
                db.sql(
                        "select user_id,roles,state,version,created_at,updated_at from"
                            + " admin_private.principals where user_id>:a order by user_id limit"
                            + " :n")
                    .param("a", after)
                    .param("n", limit)));
  }

  public Map<String, Object> change(
      AdminIdentity id,
      UUID session,
      AdminRequest request,
      UUID target,
      AdminController.Change change) {
    return run(
        id,
        session,
        request,
        "admins.manage",
        change.reason(),
        true,
        db -> {
          if (id.user().equals(target)) throw denied();
          db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
              .param("key", "admin:" + target)
              .query()
              .singleRow();
          var result =
              ActorDatabase.rows(
                  db.sql(
                          "update admin_private.principals set roles=:roles,state=:state where"
                              + " user_id=:u and version=:v returning user_id,roles,state,version")
                      .param("roles", change.roles().toArray(String[]::new))
                      .param("state", change.state())
                      .param("u", target)
                      .param("v", change.expectedVersion()));
          if (result.isEmpty())
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Principal changed or is unavailable");
          return result.getFirst();
        });
  }

  public Map<String, Object> invite(
      AdminIdentity id, UUID session, AdminRequest request, AdminController.Invite invite) {
    return run(
        id,
        session,
        request,
        "admins.manage",
        invite.reason(),
        true,
        db -> {
          if (id.user().equals(invite.userId()) || invite.roles().contains("SUPER_ADMIN"))
            throw denied();
          if (!ActorDatabase.rows(
                  db.sql("select user_id from admin_private.principals where user_id=:u")
                      .param("u", invite.userId()))
              .isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Administrator already exists");
          byte[] random = new byte[32];
          new SecureRandom().nextBytes(random);
          String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
          var row =
              ActorDatabase.one(
                  db.sql(
                          "insert into"
                              + " admin_private.invitations(user_id,roles,token_hash,created_by)"
                              + " values(:u,:roles,:hash,:actor) returning"
                              + " id,user_id,roles,expires_at")
                      .param("u", invite.userId())
                      .param("roles", invite.roles().toArray(String[]::new))
                      .param("hash", hash(token))
                      .param("actor", id.user()));
          row.put("token", token);
          return row;
        });
  }

  public Map<String, Object> activate(
      AdminIdentity id, AdminRequest request, String token, String reason) {
    return run(
        id,
        null,
        request,
        null,
        reason,
        true,
        db -> {
          db.sql("select set_config('request.admin.invite_hash',:h,true)")
              .param("h", hash(token))
              .query()
              .singleRow();
          var rows =
              ActorDatabase.rows(
                  db.sql(
                          "select id,user_id,roles from admin_private.invitations where user_id=:u"
                              + " and token_hash=:h and redeemed_at is null and expires_at>now()"
                              + " for update")
                      .param("u", id.user())
                      .param("h", hash(token)));
          if (rows.isEmpty()) throw denied();
          var invite = rows.getFirst();
          var created =
              ActorDatabase.one(
                  db.sql(
                          "insert into admin_private.principals(user_id,roles,state)"
                              + " values(:u,:roles,'active') returning user_id,roles,state,version")
                      .param("u", id.user())
                      .param("roles", ((List<?>) invite.get("roles")).toArray(String[]::new)));
          int changed =
              db.sql(
                      "update admin_private.invitations set redeemed_at=now() where id=:i and"
                          + " redeemed_at is null")
                  .param("i", UUID.fromString(invite.get("id").toString()))
                  .update();
          if (changed != 1) throw denied();
          return created;
        });
  }

  public List<Map<String, Object>> sessions(
      AdminIdentity id, UUID session, AdminRequest request, UUID after, int limit) {
    return run(
        id,
        session,
        request,
        null,
        "Review own administrator sessions",
        false,
        db ->
            ActorDatabase.rows(
                db.sql(
                        "select id,created_at,expires_at,revoked_at,agent_family,expires_at<=now()"
                            + " as expired from admin_private.sessions where user_id=:u and id>:a"
                            + " order by id limit :n")
                    .param("u", id.user())
                    .param("a", after)
                    .param("n", limit)));
  }

  public void revoke(
      AdminIdentity id, UUID session, AdminRequest request, UUID target, String reason) {
    run(
        id,
        session,
        request,
        null,
        reason,
        false,
        db -> {
          if (db.sql(
                      "update admin_private.sessions set revoked_at=now() where id=:s and"
                          + " user_id=:u and revoked_at is null")
                  .param("s", target)
                  .param("u", id.user())
                  .update()
              != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session unavailable");
          return null;
        });
  }

  public List<Map<String, Object>> audit(
      AdminIdentity id, UUID session, AdminRequest request, long before, int limit) {
    return run(
        id,
        session,
        request,
        "audit.read",
        "Review audit history",
        false,
        db ->
            ActorDatabase.rows(
                db.sql(
                        "select"
                            + " id,actor_id,action,target_id,occurred_at,request_id,reason,outcome,before_state,after_state,ip_fingerprint,agent_family"
                            + " from admin_private.audit where id<:b order by id desc limit :n")
                    .param("b", before)
                    .param("n", limit)));
  }

  /**
   * Existing privileged review keeps its original reviewer RLS, under this additional opt-in MFA
   * fence.
   */
  public <T> T delegated(
      org.springframework.security.oauth2.jwt.Jwt jwt,
      UUID session,
      jakarta.servlet.http.HttpServletRequest request,
      String permission,
      String reason,
      boolean sensitive,
      java.util.function.Supplier<T> operation) {
    if (!settings.enabled()) return operation.get();
    AdminRequest metadata = AdminRequest.from(request, settings);
    return run(
        authenticate(jwt, metadata),
        session,
        metadata,
        permission,
        reason,
        sensitive,
        db -> operation.get());
  }

  public static String hash(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException();
    }
  }
}
