package com.castly;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.castly.admin.*;
import com.castly.api.ActorDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/castly_spring_test",
      "spring.datasource.username=castly_api",
      "spring.datasource.password=castly-api-local-test",
      "castly.jwt.issuer=http://127.0.0.1:59999/auth/v1",
      "castly.jwt.mode=jwks",
      "castly.admin.enabled=true",
      "castly.admin.users-enabled=true",
      "castly.admin.enforcement-enabled=true",
      "castly.subscriptions.enabled=true",
      "castly.trust.verification-enabled=true",
      "castly.trust.reporting-enabled=true",
      "castly.trust.disputes-enabled=true",
      "castly.trust.reviews-enabled=true",
      "castly.trust.signals-enabled=true",
      "castly.phase6.analytics-enabled=true",
      "castly.phase6.analytics-key=fixture-phase6-analytics-key-at-least-32-characters",
      "castly.admin.audit-key=local-only-audit-key-at-least-32-characters",
      "castly.cors-origins=http://localhost:8081"
    })
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AdminApiTest {
  static com.sun.net.httpserver.HttpServer storageFixture;
  static final java.util.concurrent.atomic.AtomicReference<
          java.util.function.Function<com.sun.net.httpserver.HttpExchange, String>>
      storageReply = new java.util.concurrent.atomic.AtomicReference<>();

  @AfterAll
  static void stopStorageFixture() {
    if (storageFixture != null) storageFixture.stop(0);
  }

  @DynamicPropertySource
  static void prepare(DynamicPropertyRegistry registry) throws Exception {
    storageFixture =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    storageFixture.createContext(
        "/storage/v1/object/sign/portfolio/",
        exchange -> {
          try {
            String reply = storageReply.get() == null ? "{}" : storageReply.get().apply(exchange);
            byte[] bytes = reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    storageFixture.start();
    registry.add(
        "castly.jwt.issuer",
        () -> "http://127.0.0.1:" + storageFixture.getAddress().getPort() + "/auth/v1");
    registry.add("castly.jwt.public-key", () -> "sb_publishable_local_fixture");
    if (new ProcessBuilder("node", "scripts/prepare-test-db.cjs").inheritIO().start().waitFor()
        != 0) throw new IllegalStateException("Disposable DB preparation failed");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ActorDatabase data;
  @Autowired AdminService service;

  static String u(int n) {
    return "00000000-0000-0000-0000-" + String.format("%012d", n);
  }

  static final String AUTH_SESSION = "10000000-0000-0000-0000-000000000001";

  void owner(String sql) throws Exception {
    var p =
        new ProcessBuilder(
                "docker",
                "exec",
                "-i",
                "castly-spring-test-db",
                "psql",
                "-U",
                "postgres",
                "-d",
                "castly_spring_test",
                "-v",
                "ON_ERROR_STOP=1")
            .redirectErrorStream(true)
            .start();
    p.getOutputStream().write(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    p.getOutputStream().close();
    String result = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) throw new IllegalStateException(result);
  }

  @BeforeEach
  void fixture() throws Exception {
    owner(
        "truncate"
            + " enforcement_private.accounts,enforcement_private.capabilities,enforcement_private.decisions,enforcement_private.notes,enforcement_private.proposals,enforcement_private.appeals,enforcement_private.commands,enforcement_private.events,enforcement_private.notices"
            + " restart identity cascade;");
    owner(Files.readString(Path.of("scripts/admin-test-fixtures.sql")));
  }

  ResultActions call(
      String method, String path, int user, String session, String body, String aal, long age)
      throws Exception {
    var r = request(org.springframework.http.HttpMethod.valueOf(method), "/api/v1/admin" + path);
    if (user > 0)
      r.with(
          jwt()
              .jwt(
                  j ->
                      j.subject(u(user))
                          .claim("role", "authenticated")
                          .claim("aal", aal)
                          .claim("session_id", AUTH_SESSION)
                          .claim(
                              "amr",
                              List.of(
                                  Map.of(
                                      "method",
                                      "totp",
                                      "timestamp",
                                      Instant.now().minusSeconds(age).getEpochSecond())))));
    if (session != null) r.header("X-Castly-Admin-Session", session);
    if (body != null) r.contentType("application/json").content(body);
    return mvc.perform(r);
  }

  ResultActions call(String method, String path, int user, String session, String body)
      throws Exception {
    return call(method, path, user, session, body, "aal2", 0);
  }

  Map<String, Object> object(ResultActions result) throws Exception {
    return json.readValue(
        result.andReturn().getResponse().getContentAsString(),
        new com.fasterxml.jackson.core.type.TypeReference<>() {});
  }

  String login(int user) throws Exception {
    var result = object(call("POST", "/sessions", user, null, null).andExpect(status().isOk()));
    return ((Map<?, ?>) result.get("session")).get("id").toString();
  }

  String change(String roles, String state, long version) {
    return "{\"roles\":[\""
        + roles
        + "\"],\"state\":\""
        + state
        + "\",\"expected_version\":"
        + version
        + ",\"reason\":\"Reviewed access change\"}";
  }

  String invite(String s, int target) throws Exception {
    return object(
            call(
                    "POST",
                    "/invitations",
                    31,
                    s,
                    "{\"user_id\":\""
                        + u(target)
                        + "\",\"roles\":[\"ANALYST\"],\"reason\":\"Approved analyst access\"}")
                .andExpect(status().isOk()))
        .get("token")
        .toString();
  }

  @Test
  void m72DirectoryIsBoundedEscapedAndPermissionScoped() throws Exception {
    String support = login(33);
    var first = object(call("GET", "/users?limit=7", 33, support, null).andExpect(status().isOk()));
    assertEquals(7, ((List<?>) first.get("items")).size());
    String after = first.get("next_after").toString();
    var second =
        object(
            call("GET", "/users?limit=7&after=" + after, 33, support, null)
                .andExpect(status().isOk()));
    var ids = new HashSet<String>();
    for (Object page : List.of(first, second))
      for (Object row : (List<?>) ((Map<?, ?>) page).get("items"))
        assertTrue(ids.add(((Map<?, ?>) row).get("user_id").toString()));
    call("GET", "/users?limit=51", 33, support, null).andExpect(status().isBadRequest());
    call("GET", "/users?q=x", 33, support, null).andExpect(status().isBadRequest());
    call("GET", "/users?role=superadmin", 33, support, null).andExpect(status().isBadRequest());
    call("GET", "/users?from=2030-01-01&until=2020-01-01", 33, support, null)
        .andExpect(status().isBadRequest());
    assertEquals(
        List.of(),
        object(call("GET", "/users?q=zz%25_", 33, support, null).andExpect(status().isOk()))
            .get("items"));
    call("GET", "/users", 1, support, null).andExpect(status().isForbidden());
    call("GET", "/users", 34, login(34), null).andExpect(status().isForbidden());
    call("GET", "/users", 35, login(35), null).andExpect(status().isForbidden());
    call("GET", "/users", 33, support, null, "aal1", 0).andExpect(status().isForbidden());
  }

  @Test
  void m72ReadModelsCannotExposeOrForgePrivateSourceData() throws Exception {
    String support = login(33), moderator = login(32);
    call("GET", "/users", 1, support, null).andExpect(status().isForbidden());
    var supportDetail =
        object(call("GET", "/users/" + u(1), 33, support, null).andExpect(status().isOk()));
    assertNull(supportDetail.get("security_activity"));
    var superDetail =
        object(call("GET", "/users/" + u(1), 31, login(31), null).andExpect(status().isOk()));
    assertFalse(
        ((List<?>) ((Map<?, ?>) superDetail.get("security_activity")).get("events")).isEmpty());
    String text =
        call("GET", "/users/" + u(1), 33, support, null)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (String field : List.of("email", "token_hash", "requirements", "image_base64", "statement"))
      assertFalse(text.contains("\"" + field + "\""));
    call("GET", "/users/" + u(1) + "/bookings", 32, moderator, null)
        .andExpect(status().isForbidden());
    call("GET", "/users/" + u(1) + "/bookings", 33, support, null).andExpect(status().isOk());
    call("GET", "/users/ffffffff-ffff-ffff-ffff-ffffffffffff", 33, support, null)
        .andExpect(status().isNotFound());
    assertEquals(
        Integer.valueOf(0),
        data.<Integer>as(
            UUID.fromString(u(1)),
            db ->
                db.sql("select count(*) from admin_private.user_directory")
                    .query(Integer.class)
                    .single()));
    var denied =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () ->
                data.as(
                    UUID.fromString(u(1)),
                    db ->
                        db.sql(
                                "insert into"
                                    + " admin_private.user_directory(user_id,display_name,created_at,updated_at)"
                                    + " values(:u,'forged',now(),now())")
                            .param("u", UUID.fromString(u(1)))
                            .update()));
    assertEquals("42501", com.castly.api.SqlFailureKind.sqlState(denied));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            data.as(
                UUID.fromString(u(1)),
                db -> db.sql("delete from admin_private.user_history").update()));
  }

  @Test
  void m72SourceProjectionAndHistoryRollbackAtomically() throws Exception {
    UUID user = UUID.fromString(u(90));
    String support = login(33);
    data.as(
        user,
        db ->
            db.sql("update public.users set display_name='Before rollback' where id=:u")
                .param("u", user)
                .update());
    var before =
        object(call("GET", "/users/" + user, 33, support, null).andExpect(status().isOk()));
    assertThrows(
        IllegalStateException.class,
        () ->
            data.as(
                user,
                db -> {
                  db.sql("update public.users set display_name='Must rollback' where id=:u")
                      .param("u", user)
                      .update();
                  throw new IllegalStateException("fixture rollback");
                }));
    var after = object(call("GET", "/users/" + user, 33, support, null).andExpect(status().isOk()));
    assertEquals(before, after);
    assertEquals(
        1,
        ((List<?>)
                object(call("GET", "/users?q=Before", 33, support, null).andExpect(status().isOk()))
                    .get("items"))
            .size());
  }

  @Test
  void m72ConcurrentSourceChangesKeepProjectionConsistent() throws Exception {
    UUID user = UUID.fromString(u(89));
    try (var workers = Executors.newFixedThreadPool(2)) {
      var go = new CountDownLatch(1);
      var jobs = new ArrayList<Future<?>>();
      for (String name : List.of("Concurrent A", "Concurrent B"))
        jobs.add(
            workers.submit(
                () -> {
                  go.await();
                  return data.as(
                      user,
                      db ->
                          db.sql("update public.users set display_name=:n where id=:u")
                              .param("n", name)
                              .param("u", user)
                              .update());
                }));
      go.countDown();
      for (var job : jobs) job.get(10, TimeUnit.SECONDS);
    }
    String source =
        data.as(
            user,
            db ->
                db.sql("select display_name from public.users where id=:u")
                    .param("u", user)
                    .query(String.class)
                    .single());
    var detail =
        object(call("GET", "/users/" + user, 33, login(33), null).andExpect(status().isOk()));
    assertEquals(source, ((Map<?, ?>) detail.get("account")).get("display_name"));
    assertTrue(((List<?>) detail.get("history")).size() >= 2);
  }

  @Autowired com.castly.trust.VerificationService verification;

  String verificationFixture(int user) throws Exception {
    UUID asset = UUID.randomUUID();
    owner(
        "insert into public.talent_profiles(id,display_name,category_id,city,day_rate) values('"
            + u(user)
            + "','Review fixture','actors','Pune',1000) on conflict do nothing;insert into"
            + " public.portfolio_assets(id,talent_id,path,label) values('"
            + asset
            + "','"
            + u(user)
            + "','"
            + u(user)
            + "/review.jpg','Consented fixture');");
    return verification
        .submit(
            UUID.fromString(u(user)),
            "Consented portfolio review for a synthetic local account.",
            List.of(asset),
            "profile-review-v1")
        .get("id")
        .toString();
  }

  @Test
  void m72VerificationRequiresRosterPermissionFreshMfaAndCurrentRevision() throws Exception {
    String application = verificationFixture(88), superSession = login(31), support = login(33);
    String path = "/users/verifications/" + application;
    call("GET", path, 33, support, null).andExpect(status().isForbidden());
    owner(
        "delete from trust_private.reviewers where user_id='"
            + u(31)
            + "' and scope='verification'");
    call("GET", path, 31, superSession, null).andExpect(status().isForbidden());
    owner(
        "insert into trust_private.reviewers(user_id,scope) values('"
            + u(31)
            + "','verification'),('"
            + u(33)
            + "','verification') on conflict do nothing");
    try {
      call("GET", path, 33, support, null).andExpect(status().isForbidden());
      call("GET", path, 31, superSession, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.evidence[0].revision").value(1));
      String body =
          "{\"expected_revision\":1,\"expected_status\":\"PENDING\",\"decision\":\"REJECTED\",\"reason\":\"Portfolio"
              + " review rejection fixture\"}";
      call("POST", path + "/decision", 31, superSession, body, "aal2", 600)
          .andExpect(status().isForbidden());
      call("POST", path + "/decision", 31, superSession, body).andExpect(status().isOk());
      call("POST", path + "/decision", 31, superSession, body).andExpect(status().isConflict());
      call("GET", path, 31, superSession, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.history.length()").value(1))
          .andExpect(jsonPath("$.evidence.length()").value(0));
      String query = "/users?verification=REJECTED";
      assertTrue(
          call("GET", query, 33, support, null)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString()
              .contains(u(88)));
    } finally {
      owner(
          "delete from trust_private.reviewers where user_id in('"
              + u(31)
              + "','"
              + u(33)
              + "') and scope='verification'");
    }
  }

  @Test
  void m72PreviewRechecksRosterAfterStorageWait() throws Exception {
    String application = verificationFixture(84), session = login(31);
    owner(
        "insert into trust_private.reviewers(user_id,scope) values('"
            + u(31)
            + "','verification') on conflict do nothing");
    var detail = verification.adminDetail(UUID.fromString(u(31)), UUID.fromString(application));
    String evidence =
        ((Map<?, ?>) ((List<?>) detail.get("evidence")).getFirst()).get("id").toString();
    String endpoint = "/users/verifications/" + application + "/evidence/" + evidence + "/preview";
    call("POST", endpoint, 33, login(33), "{}").andExpect(status().isForbidden());
    call("POST", endpoint, 31, session, "{}", "aal2", 600).andExpect(status().isForbidden());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    storageReply.set(
        exchange -> {
          entered.countDown();
          try {
            if (!release.await(4, TimeUnit.SECONDS))
              throw new IllegalStateException("fixture release timeout");
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
          }
          String encodeHeader =
              Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(
                      "{\"alg\":\"HS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
          String encodeClaims =
              Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(
                      ("{\"exp\":" + Instant.now().plusSeconds(30).getEpochSecond() + "}")
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
          return "{\"signedURL\":\""
              + exchange.getRequestURI().getRawPath().substring("/storage/v1".length())
              + "?token="
              + encodeHeader
              + "."
              + encodeClaims
              + ".c2lnbmF0dXJl\"}";
        });
    try (var workers = Executors.newSingleThreadExecutor()) {
      var result = workers.submit(() -> call("POST", endpoint, 31, session, "{}").andReturn());
      try {
        assertTrue(
            entered.await(10, TimeUnit.SECONDS),
            "Authorized request must reach local storage fixture");
        // Only this disposable test connection is owner-authenticated, never the application.
        try (var connection =
                java.sql.DriverManager.getConnection(
                    "jdbc:postgresql://127.0.0.1:55432/castly_spring_test",
                    "postgres",
                    "local-test-only");
            var statement = connection.createStatement()) {
          statement.executeUpdate(
              "delete from trust_private.reviewers where user_id='"
                  + u(31)
                  + "' and scope='verification'");
        }
      } finally {
        release.countDown();
      }
      var response = result.get(10, TimeUnit.SECONDS).getResponse();
      assertEquals(403, response.getStatus());
      assertFalse(response.getContentAsString().contains("token="));
    } finally {
      release.countDown();
      storageReply.set(null);
      owner(
          "delete from trust_private.reviewers where user_id='"
              + u(31)
              + "' and scope='verification'");
    }
  }

  @Test
  void nonAdminAndMfaBypassFail() throws Exception {
    call("POST", "/sessions", 0, null, null).andExpect(status().isUnauthorized());
    call("POST", "/sessions", 1, null, null).andExpect(status().isForbidden());
    call("POST", "/sessions", 31, null, null, "aal1", 0).andExpect(status().isForbidden());
    call("POST", "/sessions", 31, null, null, "aal2", 600).andExpect(status().isForbidden());
    call("POST", "/sessions", 37, null, null).andExpect(status().isForbidden());
    var forged =
        post("/api/v1/admin/sessions")
            .with(
                jwt()
                    .jwt(
                        j ->
                            j.subject(u(31))
                                .claim("aal", "aal2")
                                .claim("session_id", AUTH_SESSION)
                                .claim("user_metadata", Map.of("amr", "totp"))));
    mvc.perform(forged).andExpect(status().isForbidden());
  }

  @Test
  void roleMatrixAndSessionIsolation() throws Exception {
    for (int actor = 32; actor <= 36; actor++) {
      String s = login(actor);
      call("GET", "/me", actor, s, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.permissions").isArray());
      call("GET", "/principals", actor, s, null).andExpect(status().isForbidden());
      call("GET", "/audit", actor, s, null).andExpect(status().isForbidden());
      call(
              "POST",
              "/invitations",
              actor,
              s,
              "{\"user_id\":\""
                  + u(40)
                  + "\",\"roles\":[\"ANALYST\"],\"reason\":\"Permission bypass\"}")
          .andExpect(status().isForbidden());
      call("PATCH", "/principals/" + u(34), actor, s, change("MODERATOR", "active", 1))
          .andExpect(status().isForbidden());
    }
    String s = login(31);
    call("GET", "/principals?limit=2", 31, s, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
    call("GET", "/me", 32, s, null).andExpect(status().isForbidden());
    call("GET", "/me", 31, UUID.randomUUID().toString(), null).andExpect(status().isForbidden());
  }

  @Test
  void invitationsBoundSingleUseAndNoSuperPromotion() throws Exception {
    String s = login(31), token = invite(s, 40);
    call(
            "POST",
            "/invitations/activate",
            41,
            null,
            "{\"token\":\"" + token + "\",\"reason\":\"Wrong recipient\"}")
        .andExpect(status().isForbidden());
    call(
            "POST",
            "/invitations/activate",
            40,
            null,
            "{\"token\":\"" + token + "\",\"reason\":\"Accept analyst role\"}")
        .andExpect(status().isOk());
    call(
            "POST",
            "/invitations/activate",
            40,
            null,
            "{\"token\":\"" + token + "\",\"reason\":\"Replay invitation\"}")
        .andExpect(status().isForbidden());
    String analyst = login(40);
    call("GET", "/me", 40, analyst, null).andExpect(jsonPath("$.roles[0]").value("ANALYST"));
    call("PATCH", "/principals/" + u(31), 31, s, change("SUPER_ADMIN", "active", 1))
        .andExpect(status().isForbidden());
    call("PATCH", "/principals/" + u(34), 31, s, change("SUPER_ADMIN", "active", 1))
        .andExpect(status().isForbidden());
    call("PATCH", "/principals/" + u(39), 31, s, change("ANALYST", "active", 1))
        .andExpect(status().isForbidden());
  }

  @Test
  void roleChangesInvalidateSessionsAndStaleWriteFails() throws Exception {
    String s = login(31), target = login(34);
    call("PATCH", "/principals/" + u(34), 31, s, change("MODERATOR", "active", 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    call("GET", "/me", 34, target, null).andExpect(status().isForbidden());
    call("PATCH", "/principals/" + u(34), 31, s, change("ANALYST", "active", 1))
        .andExpect(status().isConflict());
    String fresh = login(34);
    call("PATCH", "/principals/" + u(34), 31, s, change("MODERATOR", "revoked", 2))
        .andExpect(status().isOk());
    call("GET", "/me", 34, fresh, null).andExpect(status().isForbidden());
  }

  @Test
  void mfaRefreshSessionExpiryAndRevocation() throws Exception {
    String s = login(31);
    call("GET", "/me", 31, s, null, "aal2", 600).andExpect(status().isOk());
    call("PATCH", "/principals/" + u(34), 31, s, change("MODERATOR", "active", 1), "aal2", 600)
        .andExpect(status().isForbidden());
    call("DELETE", "/sessions/" + s, 32, login(32), "{\"reason\":\"Foreign session revoke\"}")
        .andExpect(status().isNotFound());
    call("DELETE", "/sessions/" + s, 31, s, "{\"reason\":\"Administrator logout\"}")
        .andExpect(status().isOk());
    call("GET", "/me", 31, s, null).andExpect(status().isForbidden());
    String exp = login(31);
    owner(
        "alter table admin_private.sessions disable trigger session_guard; update"
            + " admin_private.sessions set expires_at=now()-interval '1 minute' where id='"
            + exp
            + "'; alter table admin_private.sessions enable trigger session_guard;");
    call("GET", "/me", 31, exp, null).andExpect(status().isForbidden());
  }

  @Test
  void auditIsMandatoryAppendOnlyAndRedacted() throws Exception {
    String s = login(31);
    String token = invite(s, 40);
    var audit = call("GET", "/audit", 31, s, null).andExpect(status().isOk());
    String body = audit.andReturn().getResponse().getContentAsString();
    assertTrue(body.contains("invitation.created"));
    assertFalse(body.contains(token));
    assertFalse(body.contains(AdminService.hash(token)));
    assertFalse(body.contains("token_hash"));
    assertFalse(body.contains("127.0.0.1"));
    for (String sql :
        List.of(
            "update admin_private.audit set reason='forged'",
            "delete from admin_private.audit",
            "insert into admin_private.role_permissions values('ANALYST','admins.manage')"))
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> data.as(UUID.fromString(u(34)), db -> db.sql(sql).update()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            data.as(
                UUID.fromString(u(34)),
                db ->
                    db.sql(
                            "insert into"
                                + " admin_private.audit(actor_id,action,request_id,reason,outcome,ip_fingerprint,agent_family)"
                                + " values(private.request_user_id(),'principal.changed',gen_random_uuid(),'Forged"
                                + " source','success','','other')")
                        .update()));
    assertEquals(
        (Object) 0,
        data.as(
            UUID.fromString(u(34)),
            db ->
                db.sql("update admin_private.authorizations set roles=array['SUPER_ADMIN']")
                    .update()));
    assertEquals(
        (Object) 0,
        data.as(
            UUID.fromString(u(34)),
            db ->
                db.sql("select count(*) from admin_private.audit").query(Integer.class).single()));
  }

  @Test
  void sessionFanoutAndPayloadsAreBounded() throws Exception {
    login(31);
    login(31);
    login(31);
    call("POST", "/sessions", 31, null, null).andExpect(status().isTooManyRequests());
    String s = login(32);
    call("GET", "/principals?limit=1000", 32, s, null).andExpect(status().isBadRequest());
    call("POST", "/invitations", 32, s, "x".repeat(17000)).andExpect(status().isPayloadTooLarge());
  }

  @Test
  void concurrentInvitationRedemptionCommitsOnce() throws Exception {
    String token = invite(login(31), 40);
    var identity =
        new AdminIdentity(UUID.fromString(u(40)), UUID.fromString(AUTH_SESSION), Instant.now());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Callable<Boolean> action =
          () -> {
            try {
              service.activate(
                  identity,
                  new AdminRequest(UUID.randomUUID(), "fixture", "other"),
                  token,
                  "Concurrent activation");
              return true;
            } catch (Exception e) {
              return false;
            }
          };
      var results = executor.invokeAll(List.of(action, action));
      assertEquals(
          1,
          results.stream()
              .filter(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .count());
    }
  }

  @Test
  void adminTransactionRollbackIncludesAudit() throws Exception {
    String s = login(31);
    assertThrows(
        IllegalStateException.class,
        () ->
            data.as(
                UUID.fromString(u(31)),
                db -> {
                  service.invite(
                      new AdminIdentity(
                          UUID.fromString(u(31)), UUID.fromString(AUTH_SESSION), Instant.now()),
                      UUID.fromString(s),
                      new AdminRequest(UUID.randomUUID(), "fixture", "other"),
                      new AdminController.Invite(
                          UUID.fromString(u(40)), List.of("ANALYST"), "Rollback approval"));
                  throw new IllegalStateException("fixture rollback");
                }));
    String audit =
        call("GET", "/audit", 31, s, null).andReturn().getResponse().getContentAsString();
    assertFalse(audit.contains("invitation.created"));
  }

  @Test
  void authSessionBindingAndPooledIdentityCannotBeReused() throws Exception {
    String session = login(31);
    mvc.perform(
            get("/api/v1/admin/me")
                .header("X-Castly-Admin-Session", session)
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(u(31))
                                    .claim("aal", "aal2")
                                    .claim("session_id", UUID.randomUUID().toString())
                                    .claim(
                                        "amr",
                                        List.of(
                                            Map.of(
                                                "method",
                                                "totp",
                                                "timestamp",
                                                Instant.now().getEpochSecond()))))))
        .andExpect(status().isForbidden());
    call("GET", "/me", 31, session, null, "aal1", 0).andExpect(status().isForbidden());
    assertEquals(
        "",
        data.as(
            UUID.fromString(u(34)),
            db ->
                db.sql(
                        "select"
                            + " coalesce(nullif(current_setting('request.admin.session',true),''),'')")
                    .query(String.class)
                    .single()));
    assertEquals(
        Boolean.FALSE,
        data.as(
            UUID.fromString(u(34)),
            db ->
                db.sql("select private.admin_permission('admins.manage')")
                    .query(Boolean.class)
                    .single()));
  }

  @Test
  void expiredInvitationAndFailedAuditNeverActivate() throws Exception {
    String session = login(31), token = invite(session, 40);
    owner(
        "alter table admin_private.invitations disable trigger invite_guard; update"
            + " admin_private.invitations set expires_at=now()-interval '1 second' where user_id='"
            + u(40)
            + "'; alter table admin_private.invitations enable trigger invite_guard;");
    call(
            "POST",
            "/invitations/activate",
            40,
            null,
            "{\"token\":\"" + token + "\",\"reason\":\"Expired invitation\"}")
        .andExpect(status().isForbidden());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            service.invite(
                new AdminIdentity(
                    UUID.fromString(u(31)), UUID.fromString(AUTH_SESSION), Instant.now()),
                UUID.fromString(session),
                new AdminRequest(UUID.randomUUID(), "fixture", "other"),
                new AdminController.Invite(UUID.fromString(u(41)), List.of("ANALYST"), "x")));
    assertEquals(
        (Object) 0,
        data.as(
            UUID.fromString(u(41)),
            db ->
                db.sql(
                        "select count(*) from admin_private.invitations where"
                            + " user_id=private.request_user_id()")
                    .query(Integer.class)
                    .single()));
  }

  @Test
  void concurrentRoleChangesUseOptimisticVersion() throws Exception {
    UUID session = UUID.fromString(login(31));
    var identity =
        new AdminIdentity(UUID.fromString(u(31)), UUID.fromString(AUTH_SESSION), Instant.now());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Callable<Boolean> update =
          () -> {
            try {
              service.change(
                  identity,
                  session,
                  new AdminRequest(UUID.randomUUID(), "fixture", "other"),
                  UUID.fromString(u(34)),
                  new AdminController.Change(
                      List.of("MODERATOR"), "active", 1, "Concurrent access review"));
              return true;
            } catch (org.springframework.web.server.ResponseStatusException e) {
              assertEquals(409, e.getStatusCode().value());
              return false;
            }
          };
      var results = executor.invokeAll(List.of(update, update));
      assertEquals(
          1,
          results.stream()
              .filter(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .count());
    }
  }

  @Test
  void optInAdminGateAlsoFencesLegacyReviewWithoutGrantingReviewerMembership() throws Exception {
    String session = login(31);
    var identity =
        jwt()
            .jwt(
                j ->
                    j.subject(u(31))
                        .claim("aal", "aal2")
                        .claim("session_id", AUTH_SESSION)
                        .claim(
                            "amr",
                            List.of(
                                Map.of(
                                    "method",
                                    "totp",
                                    "timestamp",
                                    Instant.now().getEpochSecond()))));
    mvc.perform(
            get("/api/v1/admin/subscriptions/promotions")
                .with(identity)
                .header("X-Castly-Admin-Session", session))
        .andExpect(status().isForbidden());
    owner(
        "insert into private.subscription_admins values('" + u(31) + "') on conflict do nothing;");
    mvc.perform(
            get("/api/v1/admin/subscriptions/promotions")
                .with(identity)
                .header("X-Castly-Admin-Session", session))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/admin/subscriptions/promotions").with(identity))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/admin/subscriptions/promotions")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(u(31))
                                    .claim("aal", "aal1")
                                    .claim("session_id", AUTH_SESSION)))
                .header("X-Castly-Admin-Session", session))
        .andExpect(status().isForbidden());
    var submitted =
        mvc.perform(
                post("/api/v1/subscriptions/promotions/claims")
                    .with(jwt().jwt(j -> j.subject(u(2))))
                    .contentType("application/json")
                    .content(
                        "{\"post_url\":\"https://www.instagram.com/reel/admin-gated-review/\"}"))
            .andExpect(status().isOk());
    String claim = object(submitted).get("id").toString();
    mvc.perform(
            post("/api/v1/admin/subscriptions/promotions/" + claim + "/approve")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(u(31))
                                    .claim("aal", "aal2")
                                    .claim("session_id", AUTH_SESSION)
                                    .claim(
                                        "amr",
                                        List.of(
                                            Map.of(
                                                "method",
                                                "totp",
                                                "timestamp",
                                                Instant.now()
                                                    .minusSeconds(600)
                                                    .getEpochSecond())))))
                .header("X-Castly-Admin-Session", session)
                .contentType("application/json")
                .content("{\"reason\":\"Fresh MFA must be required\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/subscriptions/promotions/" + claim + "/approve")
                .with(identity)
                .header("X-Castly-Admin-Session", session)
                .contentType("application/json")
                .content("{\"reason\":\"Verified local fixture review\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("approved"));
    owner("delete from private.subscription_admins where user_id='" + u(31) + "';");
  }

  @Test
  void integratedLegacyAdminRoutesRequireBothMfaSessionAndExistingRoster() throws Exception {
    String session = login(31);
    var identity =
        jwt()
            .jwt(
                j ->
                    j.subject(u(31))
                        .claim("aal", "aal2")
                        .claim("session_id", AUTH_SESSION)
                        .claim(
                            "amr",
                            List.of(
                                Map.of(
                                    "method",
                                    "totp",
                                    "timestamp",
                                    Instant.now().getEpochSecond()))));
    for (String route :
        List.of(
            "/admin/verification",
            "/admin/moderation",
            "/admin/disputes",
            "/engagement/admin/analytics")) {
      mvc.perform(
              get("/api/v1" + route)
                  .with(identity)
                  .header("X-Castly-Admin-Session", UUID.randomUUID().toString()))
          .andExpect(status().isForbidden());
      mvc.perform(get("/api/v1" + route).with(identity).header("X-Castly-Admin-Session", session))
          .andExpect(status().isForbidden());
    }
    owner(
        "insert into trust_private.reviewers(user_id,scope) values('"
            + u(31)
            + "','verification'),('"
            + u(31)
            + "','moderation'),('"
            + u(31)
            + "','disputes') on conflict do nothing; insert into"
            + " engagement_private.analytics_admins values('"
            + u(31)
            + "') on conflict do nothing;");
    for (String route :
        List.of(
            "/admin/verification",
            "/admin/moderation",
            "/admin/disputes",
            "/engagement/admin/analytics"))
      mvc.perform(get("/api/v1" + route).with(identity).header("X-Castly-Admin-Session", session))
          .andExpect(status().isOk());
    String moderator = login(32);
    mvc.perform(
            get("/api/v1/admin/verification")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(u(32))
                                    .claim("aal", "aal2")
                                    .claim("session_id", AUTH_SESSION)
                                    .claim(
                                        "amr",
                                        List.of(
                                            Map.of(
                                                "method",
                                                "totp",
                                                "timestamp",
                                                Instant.now().getEpochSecond())))))
                .header("X-Castly-Admin-Session", moderator))
        .andExpect(status().isForbidden());
    owner(
        "delete from trust_private.reviewers where user_id='"
            + u(31)
            + "'; delete from engagement_private.analytics_admins where user_id='"
            + u(31)
            + "';");
  }

  Map<String, Object> enforcementPayload(long version, String state, int hours) {
    return Map.of(
        "version",
        version,
        "state",
        state,
        "duration_hours",
        hours,
        "reason_code",
        "ACCOUNT_COMPROMISE",
        "explanation",
        "Protective account security hold.",
        "evidence_ref",
        "local:support-case",
        "notes",
        "Confidential local investigation only",
        "denied",
        List.of("discovery"));
  }

  ResultActions accountCommand(
      int admin,
      String session,
      int target,
      String operation,
      Map<String, Object> payload,
      String key)
      throws Exception {
    return call(
        "POST",
        "/users/" + u(target) + "/access",
        admin,
        session,
        json.writeValueAsString(
            Map.of("request_key", key, "operation", operation, "payload", payload)));
  }

  ResultActions accountCommand(
      int admin, String session, int target, String operation, Map<String, Object> payload)
      throws Exception {
    return accountCommand(admin, session, target, operation, payload, UUID.randomUUID().toString());
  }

  String accountState(int admin, String session, int target) throws Exception {
    return object(
            call("GET", "/users/" + u(target) + "/access", admin, session, null)
                .andExpect(status().isOk()))
        .get("state")
        .toString();
  }

  @Test
  void accountAuthorityAndDurationAreEnforcedInTheDatabase() throws Exception {
    String mod = login(32),
        support = login(33),
        ops = login(36),
        finance = login(35),
        analyst = login(34);
    for (var caller :
        List.of(Map.entry(33, support), Map.entry(35, finance), Map.entry(34, analyst)))
      accountCommand(
              caller.getKey(),
              caller.getValue(),
              80,
              "ENFORCE",
              enforcementPayload(0, "TEMPORARILY_SUSPENDED", 1))
          .andExpect(status().isForbidden());
    accountCommand(32, mod, 80, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 169))
        .andExpect(status().isConflict());
    accountCommand(32, mod, 80, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 24))
        .andExpect(status().isOk());
    assertEquals("TEMPORARILY_SUSPENDED", accountState(32, mod, 80));
    accountCommand(32, mod, 80, "ENFORCE", enforcementPayload(1, "TEMPORARILY_SUSPENDED", 168))
        .andExpect(status().isConflict());
    accountCommand(36, ops, 81, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 721))
        .andExpect(status().isConflict());
    accountCommand(36, ops, 81, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 720))
        .andExpect(status().isOk());
    accountCommand(32, mod, 82, "PROPOSE", enforcementPayload(0, "PERMANENTLY_SUSPENDED", 0))
        .andExpect(status().isForbidden());
    accountCommand(
            33,
            support,
            82,
            "ESCALATE",
            Map.of("notes", "Escalate reported behavior for independent review"))
        .andExpect(status().isOk());
    assertEquals("ACTIVE", accountState(33, support, 82));
  }

  @Test
  void accountCommandsRequireRecentMfaAndCannotTargetAdministrators() throws Exception {
    String session = login(31);
    var body =
        json.writeValueAsString(
            Map.of(
                "request_key",
                UUID.randomUUID(),
                "operation",
                "ENFORCE",
                "payload",
                enforcementPayload(0, "TEMPORARILY_SUSPENDED", 1)));
    call("POST", "/users/" + u(80) + "/access", 31, session, body, "aal2", 301)
        .andExpect(status().isForbidden());
    call("POST", "/users/" + u(80) + "/access", 31, session, body, "aal1", 0)
        .andExpect(status().isForbidden());
    accountCommand(31, session, 31, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 1))
        .andExpect(status().isForbidden());
    accountCommand(31, session, 32, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 1))
        .andExpect(status().isForbidden());
  }

  @Test
  void permanentSuspensionAndReinstatementRequireDistinctSuperAdministrators() throws Exception {
    String first = login(31), second = login(39);
    var proposal =
        object(
            accountCommand(
                    31, first, 80, "PROPOSE", enforcementPayload(0, "PERMANENTLY_SUSPENDED", 0))
                .andExpect(status().isOk()));
    assertEquals("ACTIVE", accountState(31, first, 80));
    var approval = Map.<String, Object>of("proposal_id", proposal.get("resource_id"));
    accountCommand(31, first, 80, "APPROVE", approval).andExpect(status().isForbidden());
    accountCommand(39, second, 80, "APPROVE", approval).andExpect(status().isOk());
    assertEquals("PERMANENTLY_SUSPENDED", accountState(31, first, 80));
    accountCommand(31, first, 80, "REINSTATE", enforcementPayload(1, "ACTIVE", 0))
        .andExpect(status().isForbidden());
    var reinstate =
        object(
            accountCommand(31, first, 80, "PROPOSE", enforcementPayload(1, "ACTIVE", 0))
                .andExpect(status().isOk()));
    accountCommand(39, second, 80, "APPROVE", Map.of("proposal_id", reinstate.get("resource_id")))
        .andExpect(status().isOk());
    assertEquals("ACTIVE", accountState(31, first, 80));
  }

  @Test
  void accountCommandsAreIdempotentVersionedAndImmutable() throws Exception {
    String mod = login(32), key = UUID.randomUUID().toString();
    var payload = enforcementPayload(0, "TEMPORARILY_SUSPENDED", 1);
    var first =
        object(accountCommand(32, mod, 80, "ENFORCE", payload, key).andExpect(status().isOk()));
    assertEquals(
        first,
        object(accountCommand(32, mod, 80, "ENFORCE", payload, key).andExpect(status().isOk())));
    accountCommand(32, mod, 80, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 2), key)
        .andExpect(status().isConflict());
    accountCommand(32, mod, 80, "REINSTATE", enforcementPayload(0, "ACTIVE", 0))
        .andExpect(status().isConflict());
    assertEquals(
        Integer.valueOf(0),
        data.<Integer>as(
            UUID.fromString(u(80)),
            db -> db.sql("update enforcement_private.accounts set state='ACTIVE'").update()));
    assertEquals("TEMPORARILY_SUSPENDED", accountState(32, mod, 80));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            data.as(
                UUID.fromString(u(80)),
                db -> db.sql("delete from enforcement_private.events").update()));
    assertEquals(
        Integer.valueOf(1),
        data.<Integer>as(
            UUID.fromString(u(80)),
            db ->
                db.sql("select count(*) from enforcement_private.notices")
                    .query(Integer.class)
                    .single()));
    assertEquals(
        Integer.valueOf(0),
        data.<Integer>as(
            UUID.fromString(u(81)),
            db ->
                db.sql("select count(*) from enforcement_private.notices")
                    .query(Integer.class)
                    .single()));
  }

  @Test
  void temporaryExpiryUsesDatabaseTimeAndPreservesHistory() throws Exception {
    String mod = login(32);
    accountCommand(32, mod, 80, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 1))
        .andExpect(status().isOk());
    assertFalse(
        data.<Boolean>as(
            UUID.fromString(u(80)),
            db ->
                db.sql(
                        "select"
                            + " enforcement_private.allowed(enforcement_private.actor(),'messaging')")
                    .query(Boolean.class)
                    .single()));
    owner(
        "update enforcement_private.accounts set until_at=clock_timestamp()-interval '1 second'"
            + " where user_id='"
            + u(80)
            + "';");
    assertEquals("ACTIVE", accountState(32, mod, 80));
    assertTrue(
        data.<Boolean>as(
            UUID.fromString(u(80)),
            db ->
                db.sql(
                        "select"
                            + " enforcement_private.allowed(enforcement_private.actor(),'messaging')")
                    .query(Boolean.class)
                    .single()));
    assertEquals(
        Integer.valueOf(1),
        data.<Integer>as(
            UUID.fromString(u(80)),
            db ->
                db.sql("select count(*) from enforcement_private.decisions")
                    .query(Integer.class)
                    .single()));
  }

  ResultActions ownAppeal(int user, String operation, Map<String, Object> payload)
      throws Exception {
    return mvc.perform(
        post("/api/v1/account/appeals")
            .with(jwt().jwt(j -> j.subject(u(user))))
            .contentType("application/json")
            .content(
                json.writeValueAsString(
                    Map.of(
                        "request_key",
                        UUID.randomUUID(),
                        "operation",
                        operation,
                        "payload",
                        payload))));
  }

  @Test
  void appealsRequireOwnershipDeadlinesIndependentReviewAndDoNotLiftRestrictions()
      throws Exception {
    String mod = login(32), ops = login(36);
    var result =
        object(
            accountCommand(
                    32, mod, 80, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 24))
                .andExpect(status().isOk()));
    var appeal =
        Map.<String, Object>of(
            "decision_id",
            result.get("resource_id"),
            "statement",
            "Please independently review this protective hold.",
            "evidence_ref",
            "local:new-case");
    ownAppeal(81, "APPEAL_SUBMIT", appeal).andExpect(status().isConflict());
    var submitted = object(ownAppeal(80, "APPEAL_SUBMIT", appeal).andExpect(status().isOk()));
    ownAppeal(80, "APPEAL_SUBMIT", appeal).andExpect(status().isConflict());
    assertEquals("TEMPORARILY_SUSPENDED", accountState(32, mod, 80));
    var review =
        Map.<String, Object>of("appeal_id", submitted.get("resource_id"), "appeal_version", 1);
    accountCommand(32, mod, 80, "APPEAL_REVIEW", review).andExpect(status().isForbidden());
    accountCommand(36, ops, 80, "APPEAL_REVIEW", review).andExpect(status().isOk());
    var decision = new HashMap<String, Object>(enforcementPayload(1, "ACTIVE", 0));
    decision.putAll(
        Map.of(
            "appeal_id",
            submitted.get("resource_id"),
            "appeal_version",
            2,
            "outcome",
            "OVERTURNED",
            "resolution",
            "Independent evidence supports reinstatement."));
    accountCommand(
            36,
            ops,
            80,
            "APPEAL_DECIDE",
            Map.of(
                "appeal_id",
                submitted.get("resource_id"),
                "appeal_version",
                2,
                "outcome",
                "OVERTURNED",
                "resolution",
                "Resolution without the mandatory decision evidence must rollback."))
        .andExpect(status().isConflict());
    assertEquals("TEMPORARILY_SUSPENDED", accountState(36, ops, 80));
    var forbiddenPermanent = new HashMap<String, Object>(decision);
    forbiddenPermanent.put("state", "PERMANENTLY_SUSPENDED");
    forbiddenPermanent.put("outcome", "MODIFIED");
    accountCommand(36, ops, 80, "APPEAL_DECIDE", forbiddenPermanent)
        .andExpect(status().isForbidden());
    assertEquals("TEMPORARILY_SUSPENDED", accountState(36, ops, 80));
    accountCommand(36, ops, 80, "APPEAL_DECIDE", decision).andExpect(status().isOk());
    assertEquals("ACTIVE", accountState(36, ops, 80));
    owner(
        "update enforcement_private.decisions set notified_at=clock_timestamp()-interval '31 days'"
            + " where id='"
            + result.get("resource_id")
            + "';");
    ownAppeal(80, "APPEAL_SUBMIT", appeal).andExpect(status().isConflict());
  }

  @Test
  void suspensionSerializesWithBookingCreationAndPreservesCounterpartyObligations()
      throws Exception {
    owner(
        "insert into"
            + " public.talent_profiles(id,display_name,category_id,city,day_rate,bio,skills,published)"
            + " values('"
            + u(80)
            + "','Concurrency fixture','actors','Pune',1000,'Fixture','{}',true) on conflict(id) do"
            + " update set published=true;");
    String mod = login(32);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var booking =
          executor.submit(
              () ->
                  data.as(
                      UUID.fromString(u(1)),
                      db -> {
                        var row =
                            ActorDatabase.one(
                                db.sql(
                                        "insert into"
                                            + " public.bookings(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget)"
                                            + " values(:c,:t,'Serial"
                                            + " obligation',current_date+2,'Pune','Concurrency"
                                            + " fixture',1000) returning id")
                                    .param("c", UUID.fromString(u(1)))
                                    .param("t", UUID.fromString(u(80))));
                        entered.countDown();
                        try {
                          if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("Release timeout");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new RuntimeException(e);
                        }
                        return row;
                      }));
      assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
      var hold =
          executor.submit(
              () ->
                  accountCommand(
                          32, mod, 80, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 1))
                      .andExpect(status().isOk()));
      // Observe the real PostgreSQL lock waiter, rather than depending on a scheduler sleep.
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
      boolean waiting = false;
      while (System.nanoTime() < deadline && !waiting) {
        waiting =
            data.as(
                UUID.fromString(u(1)),
                db ->
                    db.sql(
                            "select exists(select 1 from pg_locks where locktype='advisory' and not"
                                + " granted)")
                        .query(Boolean.class)
                        .single());
        Thread.onSpinWait();
      }
      assertTrue(waiting, "Restriction must wait for the business transaction's account lock");
      assertFalse(hold.isDone());
      release.countDown();
      var existing = booking.get(10, java.util.concurrent.TimeUnit.SECONDS);
      hold.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          Integer.valueOf(1),
          data.<Integer>as(
              UUID.fromString(u(1)),
              db ->
                  db.sql("select count(*) from public.bookings where id=:b")
                      .param("b", UUID.fromString(existing.get("id").toString()))
                      .query(Integer.class)
                      .single()));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              data.as(
                  UUID.fromString(u(1)),
                  db ->
                      db.sql(
                              "insert into"
                                  + " public.bookings(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget)"
                                  + " values(:c,:t,'Blocked new"
                                  + " request',current_date+2,'Pune','Isolated concurrency"
                                  + " fixture',1000)")
                          .param("c", UUID.fromString(u(1)))
                          .param("t", UUID.fromString(u(80)))
                          .update()));
      assertEquals(
          Integer.valueOf(1),
          data.<Integer>as(
              UUID.fromString(u(1)),
              db ->
                  db.sql("update public.bookings set status='cancelled' where id=:b")
                      .param("b", UUID.fromString(existing.get("id").toString()))
                      .update()));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void appealReopeningRequiresNewEvidenceAndIndependentDetermination() throws Exception {
    String mod = login(32), ops = login(36);
    var decision =
        object(
            accountCommand(
                    32, mod, 80, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 24))
                .andExpect(status().isOk()));
    var submitted =
        object(
            ownAppeal(
                    80,
                    "APPEAL_SUBMIT",
                    Map.of(
                        "decision_id",
                        decision.get("resource_id"),
                        "statement",
                        "Please review this case with independent evidence.",
                        "evidence_ref",
                        "local:initial"))
                .andExpect(status().isOk()));
    String id = submitted.get("resource_id").toString();
    ownAppeal(80, "APPEAL_WITHDRAW", Map.of("appeal_id", id, "appeal_version", 1))
        .andExpect(status().isOk());
    ownAppeal(
            80,
            "APPEAL_REOPEN_REQUEST",
            Map.of(
                "appeal_id",
                id,
                "appeal_version",
                2,
                "statement",
                "Significant new evidence available for independent review.",
                "evidence_ref",
                "local:initial"))
        .andExpect(status().isConflict());
    ownAppeal(
            80,
            "APPEAL_REOPEN_REQUEST",
            Map.of(
                "appeal_id",
                id,
                "appeal_version",
                2,
                "statement",
                "Significant new evidence available for independent review.",
                "evidence_ref",
                "local:significant-new"))
        .andExpect(status().isOk());
    accountCommand(
            32,
            mod,
            80,
            "APPEAL_REOPEN",
            Map.of("appeal_id", id, "appeal_version", 3, "significant", true))
        .andExpect(status().isForbidden());
    accountCommand(
            36,
            ops,
            80,
            "APPEAL_REOPEN",
            Map.of("appeal_id", id, "appeal_version", 3, "significant", false))
        .andExpect(status().isConflict());
    accountCommand(
            36,
            ops,
            80,
            "APPEAL_REOPEN",
            Map.of("appeal_id", id, "appeal_version", 3, "significant", true))
        .andExpect(status().isOk());
    assertEquals("TEMPORARILY_SUSPENDED", accountState(36, ops, 80));
  }

  void seededRestrictedTalent() throws Exception {
    owner(
        "insert into"
            + " public.talent_profiles(id,display_name,category_id,city,day_rate,bio,skills,published)"
            + " values('"
            + u(80)
            + "','Concurrency fixture','actors','Pune',1000,'Fixture','{}',true) on conflict(id) do"
            + " update set published=true;");
  }

  void assertMutationSerialized(
      java.util.function.Function<org.springframework.jdbc.core.simple.JdbcClient, Object> mutation)
      throws Exception {
    String mod = login(32);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var business =
          executor.submit(
              () ->
                  data.as(
                      UUID.fromString(u(80)),
                      db -> {
                        Object result = mutation.apply(db);
                        entered.countDown();
                        try {
                          if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("Release timeout");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new RuntimeException(e);
                        }
                        return result;
                      }));
      assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
      var hold =
          executor.submit(
              () ->
                  accountCommand(
                          32, mod, 80, "ENFORCE", enforcementPayload(0, "TEMPORARILY_SUSPENDED", 1))
                      .andExpect(status().isOk()));
      boolean waiting = false;
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
      while (System.nanoTime() < deadline && !waiting) {
        waiting =
            data.as(
                UUID.fromString(u(1)),
                db ->
                    db.sql(
                            "select exists(select 1 from pg_locks where locktype='advisory' and not"
                                + " granted)")
                        .query(Boolean.class)
                        .single());
        Thread.onSpinWait();
      }
      assertTrue(waiting, "Restriction must serialize with the actual business trigger");
      assertFalse(hold.isDone());
      release.countDown();
      business.get(10, java.util.concurrent.TimeUnit.SECONDS);
      hold.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> data.as(UUID.fromString(u(80)), mutation));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void restrictionSerializesWithPublicProfilePublication() throws Exception {
    seededRestrictedTalent();
    assertMutationSerialized(
        db ->
            db.sql("update public.talent_profiles set published=true where id=:u")
                .param("u", UUID.fromString(u(80)))
                .update());
  }

  @Test
  void restrictionSerializesWithMessageSending() throws Exception {
    seededRestrictedTalent();
    owner(
        "insert into"
            + " public.bookings(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget,status)"
            + " values('"
            + u(1)
            + "','"
            + u(80)
            + "','Existing message obligation',current_date+2,'Pune','Isolated concurrency"
            + " fixture',1000,'accepted');insert into public.conversations(client_id,talent_id)"
            + " values('"
            + u(1)
            + "','"
            + u(80)
            + "') on conflict do nothing;");
    UUID conversation =
        data.as(
            UUID.fromString(u(80)),
            db ->
                db.sql("select id from public.conversations where client_id=:c and talent_id=:t")
                    .param("c", UUID.fromString(u(1)))
                    .param("t", UUID.fromString(u(80)))
                    .query(UUID.class)
                    .single());
    assertMutationSerialized(
        db ->
            db.sql(
                    "insert into public.messages(conversation_id,sender_id,body)"
                        + " values(:c,:u,'Local concurrency fixture')")
                .param("c", conversation)
                .param("u", UUID.fromString(u(80)))
                .update());
    assertEquals(
        Integer.valueOf(1),
        data.<Integer>as(
            UUID.fromString(u(1)),
            db ->
                db.sql(
                        "insert into public.messages(conversation_id,sender_id,body)"
                            + " values(:c,:u,'Counterparty retains existing access')")
                    .param("c", conversation)
                    .param("u", UUID.fromString(u(1)))
                    .update()));
  }

  @Test
  void restrictionSerializesWithBookingAcceptance() throws Exception {
    seededRestrictedTalent();
    owner(
        "insert into"
            + " public.bookings(id,client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget)"
            + " values('74000000-0000-0000-0000-000000000001','"
            + u(1)
            + "','"
            + u(80)
            + "','Acceptance race',current_date+2,'Pune','Isolated concurrency fixture',1000) on"
            + " conflict do nothing;insert into"
            + " public.bookings(id,client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget)"
            + " values('74000000-0000-0000-0000-000000000002','"
            + u(1)
            + "','"
            + u(80)
            + "','Second acceptance race',current_date+2,'Pune','Isolated concurrency"
            + " fixture',1000) on conflict do nothing;");
    assertMutationSerialized(
        db -> {
          // A fresh requested row is necessary for each acceptance attempt; no status reset bypass.
          var requested =
              ActorDatabase.rows(
                  db.sql(
                          "select id from public.bookings where client_id=:c and talent_id=:t and"
                              + " status='requested' order by id limit 1")
                      .param("c", UUID.fromString(u(1)))
                      .param("t", UUID.fromString(u(80))));
          if (requested.isEmpty())
            throw new IllegalStateException("Missing requested booking fixture");
          return db.sql("update public.bookings set status='accepted' where id=:b")
              .param("b", UUID.fromString(requested.getFirst().get("id").toString()))
              .update();
        });
  }

  @Test
  void permanentAppealOverturnNeedsTwoIndependentSuperAdministrators() throws Exception {
    owner(
        "begin;set local castly.admin_bootstrap_reviewed='M7.1-owner-approved';insert into"
            + " admin_private.principals(user_id,roles,state) values('"
            + u(40)
            + "',array['SUPER_ADMIN'],'active'),('"
            + u(41)
            + "',array['SUPER_ADMIN'],'active');commit;");
    String first = login(31), second = login(39), reviewer = login(40), approver = login(41);
    var proposal =
        object(
            accountCommand(
                    31, first, 80, "PROPOSE", enforcementPayload(0, "PERMANENTLY_SUSPENDED", 0))
                .andExpect(status().isOk()));
    var permanent =
        object(
            accountCommand(
                    39, second, 80, "APPROVE", Map.of("proposal_id", proposal.get("resource_id")))
                .andExpect(status().isOk()));
    var appeal =
        object(
            ownAppeal(
                    80,
                    "APPEAL_SUBMIT",
                    Map.of(
                        "decision_id",
                        permanent.get("resource_id"),
                        "statement",
                        "Independent review requested with significant account evidence.",
                        "evidence_ref",
                        "local:permanent-appeal"))
                .andExpect(status().isOk()));
    var review =
        Map.<String, Object>of("appeal_id", appeal.get("resource_id"), "appeal_version", 1);
    accountCommand(31, first, 80, "APPEAL_REVIEW", review).andExpect(status().isForbidden());
    accountCommand(39, second, 80, "APPEAL_REVIEW", review).andExpect(status().isForbidden());
    accountCommand(40, reviewer, 80, "APPEAL_REVIEW", review).andExpect(status().isOk());
    var payload = new HashMap<String, Object>(enforcementPayload(1, "ACTIVE", 0));
    payload.putAll(
        Map.of(
            "appeal_id",
            appeal.get("resource_id"),
            "appeal_version",
            2,
            "outcome",
            "OVERTURNED",
            "resolution",
            "Independent appeal evidence supports reinstatement."));
    var overturn =
        object(
            accountCommand(40, reviewer, 80, "APPEAL_DECIDE", payload).andExpect(status().isOk()));
    assertEquals("PERMANENTLY_SUSPENDED", accountState(31, first, 80));
    var approval = Map.<String, Object>of("proposal_id", overturn.get("resource_id"));
    accountCommand(40, reviewer, 80, "APPROVE", approval).andExpect(status().isForbidden());
    accountCommand(31, first, 80, "APPROVE", approval).andExpect(status().isForbidden());
    accountCommand(41, approver, 80, "APPROVE", approval).andExpect(status().isOk());
    assertEquals("ACTIVE", accountState(41, approver, 80));
  }
}
