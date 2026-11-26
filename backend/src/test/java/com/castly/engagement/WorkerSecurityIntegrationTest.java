package com.castly.engagement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.castly.api.ActorDatabase;
import com.castly.discovery.Phase5Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

/** Real, separately authenticated PostgreSQL sessions; only outbound providers are mocks. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class WorkerSecurityIntegrationTest {
  private static final String DATABASE = "castly_worker_security_test";
  private static final String URL = "jdbc:postgresql://127.0.0.1:55432/" + DATABASE;
  private static final String PASSWORD = "worker-security-local-only";
  private static final String USER = "castly_engagement_worker";
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private HikariDataSource apiPool;
  private ActorDatabase actors;
  private final Phase5Service discovery = mock(Phase5Service.class);
  private final ObjectMapper json = new ObjectMapper();

  @BeforeAll
  void prepare() throws Exception {
    var command = new ProcessBuilder("node", "scripts/prepare-test-db.cjs").inheritIO();
    command.environment().put("CASTLY_TEST_DATABASE", DATABASE);
    assertEquals(0, command.start().waitFor(), "Disposable PostgreSQL preparation failed");
    owner(
        "alter role "
            + USER
            + " login noinherit nosuperuser nobypassrls nocreatedb nocreaterole noreplication"
            + " password '"
            + PASSWORD
            + "'");
    apiPool = pool("castly_api", "castly-api-local-test", null);
    actors = new ActorDatabase(apiPool, new DataSourceTransactionManager(apiPool));
    actors.afterPropertiesSet();
  }

  @AfterAll
  void closeApi() {
    if (apiPool != null) apiPool.close();
  }

  @BeforeEach
  void rows() throws Exception {
    owner(
        "delete from engagement_private.outbox;delete from public.push_devices;delete from"
            + " public.engagement_preferences;delete from engagement_private.scan_state;delete from"
            + " engagement_private.budgets;delete from engagement_private.events;insert into"
            + " public.engagement_preferences(user_id,push_bookings,reminders,quiet_start,quiet_end)"
            + " values('"
            + CLIENT
            + "',true,true,0,0);"
            + "insert into public.push_devices(user_id,device_key,token,platform) values('"
            + CLIENT
            + "',gen_random_uuid(),'ExpoPushToken[worker_security_fixture]','android')");
  }

  private String owner(String sql) throws Exception {
    var process =
        new ProcessBuilder(
                "docker",
                "exec",
                "-i",
                "castly-spring-test-db",
                "psql",
                "-X",
                "-At",
                "-U",
                "postgres",
                "-d",
                DATABASE,
                "-v",
                "ON_ERROR_STOP=1")
            .redirectErrorStream(true)
            .start();
    try (var input = process.getOutputStream()) {
      input.write(sql.getBytes(StandardCharsets.UTF_8));
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    return output.trim();
  }

  private HikariDataSource pool(String user, String password, String init) {
    var pool = new HikariDataSource();
    pool.setJdbcUrl(URL);
    pool.setUsername(user);
    pool.setPassword(password);
    pool.setMaximumPoolSize(2);
    pool.setConnectionTimeout(1500);
    pool.setInitializationFailTimeout(1000);
    pool.setPoolName("worker-security-" + UUID.randomUUID());
    pool.addDataSourceProperty("ApplicationName", pool.getPoolName());
    if (init != null) pool.setConnectionInitSql(init);
    return pool;
  }

  private Phase6Settings flags(boolean enabled) {
    var f = new Phase6Settings();
    f.worker = enabled;
    f.push = true;
    f.pushProvider = "expo";
    f.appOrigin = "https://app.fixture.invalid";
    f.apiOrigin = "https://api.fixture.invalid";
    return f;
  }

  private EngagementWorker worker(
      HikariDataSource source, Phase6Settings f, DeliveryProvider provider) {
    return new EngagementWorker(source, actors, discovery, f, provider, json);
  }

  private long count(String table) throws Exception {
    return Long.parseLong(owner("select count(*) from " + table));
  }

  private UUID queue() throws Exception {
    return UUID.fromString(
        owner(
                "insert into"
                    + " engagement_private.outbox(recipient_id,event_key,kind,channel,device_id)"
                    + " select '"
                    + CLIENT
                    + "','worker-fixture:"
                    + UUID.randomUUID()
                    + "','account_activity','push',id from public.push_devices where user_id='"
                    + CLIENT
                    + "' returning id")
            .split("\\R")[0]);
  }

  private String state(UUID id) throws Exception {
    return owner(
        "select state||':'||attempts from engagement_private.outbox where id='" + id + "'");
  }

  private DeliveryProvider provider(DeliveryProvider.Status status) {
    var p = mock(DeliveryProvider.class);
    when(p.send(any())).thenReturn(DeliveryProvider.Result.of(status));
    when(p.receipt(any()))
        .thenReturn(DeliveryProvider.Result.of(DeliveryProvider.Status.DELIVERED));
    return p;
  }

  private ApplicationContextRunner context(
      boolean enabled, String url, String user, String password) {
    var f = new Phase6Settings();
    f.worker = enabled;
    return new ApplicationContextRunner()
        .withUserConfiguration(WorkerConfiguration.class)
        .withBean(ActorDatabase.class, () -> actors)
        .withBean(
            com.castly.enforcement.AccountEnforcement.class,
            () -> new com.castly.enforcement.AccountEnforcement(actors, json, false))
        .withBean(Phase5Service.class, () -> discovery)
        .withBean(Phase6Settings.class, () -> f)
        .withBean(Phase6Signals.class, () -> new Phase6Signals(f))
        .withBean(ObjectMapper.class, () -> json)
        .withPropertyValues(
            "castly.phase6.worker-enabled=" + enabled,
            "castly.phase6.worker-url=" + url,
            "castly.phase6.worker-user=" + user,
            "castly.phase6.worker-password=" + password);
  }

  @Test
  void realLoginIdentityAndEffectivePrivilegesAreRestricted() throws Exception {
    try (var source = pool(USER, PASSWORD, null);
        var worker = worker(source, flags(false), provider(DeliveryProvider.Status.DELIVERED))) {
      var db = JdbcClient.create(source);
      var row =
          db.sql(
                  "select current_user::text as effective,session_user::text as login,(select"
                      + " usename from pg_stat_activity where pid=pg_backend_pid()) as"
                      + " authenticated,rolsuper,rolbypassrls,rolcreatedb,rolcreaterole,rolreplication,rolinherit,exists(select"
                      + " 1 from pg_auth_members where member=r.oid) as memberships,exists(select 1"
                      + " from pg_class where relowner=r.oid and relkind in('r','p')) as"
                      + " owns_tables from pg_roles r where rolname=current_user")
              .query()
              .singleRow();
      assertEquals(USER, row.get("effective"));
      assertEquals(USER, row.get("login"));
      assertEquals(USER, row.get("authenticated"));
      var evidence = new LinkedHashMap<String, Object>(row);
      evidence.put("scope", "disposable local PostgreSQL only; no hosted validation");
      evidence.put(
          "server_version_num",
          db.sql("select current_setting('server_version_num')").query(String.class).single());
      java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
      java.nio.file.Files.writeString(
          java.nio.file.Path.of("target", "worker-identity-evidence.json"),
          json.writeValueAsString(evidence));
      db.sql("reset session authorization").update();
      assertEquals(USER, db.sql("select session_user::text").query(String.class).single());
      for (String key :
          List.of(
              "rolsuper",
              "rolbypassrls",
              "rolcreatedb",
              "rolcreaterole",
              "rolreplication",
              "rolinherit",
              "memberships",
              "owns_tables")) assertEquals(false, row.get(key), key);
      for (String sql :
          List.of(
              "select * from public.users",
              "select * from public.messages",
              "select * from public.subscription_orders",
              "select requirements from public.bookings",
              "select * from public.booking_disputes",
              "select public.complete_onboarding('client','spoofed')")) {
        var denial =
            assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.sql(sql).query().listOfRows(),
                sql);
        assertEquals("42501", com.castly.api.SqlFailureKind.sqlState(denial), sql);
      }
      for (String sql :
          List.of(
              "update public.bookings set status='accepted'",
              "insert into engagement_private.analytics_admins values(gen_random_uuid())",
              "set role postgres",
              "set role authenticated")) {
        var denial =
            assertThrows(
                org.springframework.dao.DataAccessException.class, () -> db.sql(sql).update(), sql);
        assertEquals("42501", com.castly.api.SqlFailureKind.sqlState(denial), sql);
      }
      assertEquals(
          0, db.sql("select count(*) from public.push_devices").query(Integer.class).single());
      UUID job = queue();
      assertEquals(
          1, db.sql("select count(*) from public.push_devices").query(Integer.class).single());
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              db.sql(
                      "update engagement_private.outbox set recipient_id=gen_random_uuid() where"
                          + " id=:id")
                  .param("id", job)
                  .update());
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              db.sql(
                      "insert into engagement_private.outbox(recipient_id,event_key,kind,channel)"
                          + " values(:u,'forged','account_activity','email')")
                  .param("u", CLIENT)
                  .update());
    }
  }

  @Test
  void elevatedSessionCannotMasqueradeUsingSetRole() throws Exception {
    // Regression: current_user alone is insufficient when an elevated session can RESET ROLE.
    for (String init :
        List.of(
            "set role castly_engagement_worker",
            "set session authorization castly_engagement_worker")) {
      try (var source = pool("postgres", "local-test-only", init)) {
        var db = JdbcClient.create(source);
        assertEquals(USER, db.sql("select current_user::text").query(String.class).single());
        assertEquals(
            "postgres",
            db.sql("select usename from pg_stat_activity where pid=pg_backend_pid()")
                .query(String.class)
                .single());
        assertThrows(
            IllegalStateException.class,
            () -> {
              try (var ignored =
                  worker(source, flags(false), provider(DeliveryProvider.Status.DELIVERED))) {}
              ;
            });
      }
    }
  }

  @Test
  void disabledTickAndDirectScheduleCannotPerformWork() throws Exception {
    UUID job = queue();
    var f = flags(false);
    f.engagement = true;
    var provider = provider(DeliveryProvider.Status.DELIVERED);
    try (var source = pool(USER, PASSWORD, null);
        var worker = worker(source, f, provider)) {
      new WorkerConfiguration.Tick(worker).run();
      worker.schedule(Instant.now());
      assertEquals("queued:0", state(job));
      assertEquals(0, count("engagement_private.scan_state"));
      verifyNoInteractions(provider);
    }
  }

  @Test
  void disabledWithoutConnectionDoesNotInitializeWorker() {
    context(false, "", "", "")
        .run(
            c -> {
              assertNull(c.getStartupFailure());
              assertTrue(c.getBeansOfType(EngagementWorker.class).isEmpty());
              assertTrue(c.getBeansOfType(WorkerConfiguration.Tick.class).isEmpty());
            });
  }

  @Test
  void configuredControlPlaneInitializesWhileSendingDisabledAndClosesCleanly() throws Exception {
    var actual = new AtomicReference<HikariDataSource>();
    var pid = new AtomicInteger();
    context(false, URL, USER, PASSWORD)
        .run(
            c -> {
              assertNull(c.getStartupFailure());
              var worker = c.getBean(EngagementWorker.class);
              var pool = (HikariDataSource) ReflectionTestUtils.getField(worker, "source");
              actual.set(pool);
              pid.set(
                  JdbcClient.create(pool)
                      .sql("select pg_backend_pid()")
                      .query(Integer.class)
                      .single());
              c.getBean(WorkerConfiguration.Tick.class).run();
              assertFalse(pool.isClosed());
              assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            });
    assertTrue(actual.get().isClosed());
    assertEquals("0", owner("select count(*) from pg_stat_activity where pid=" + pid.get()));
  }

  @Test
  void invalidConfigurationNeverFallsBackToBusinessCredentials(CapturedOutput output) {
    String secret = "DO_NOT_LOG_WORKER_PASSWORD_SENTINEL";
    for (String user : List.of("postgres", "castly_api", "service_role", "authenticated"))
      context(true, URL, user, secret)
          .run(c -> assertFailure(c.getStartupFailure(), "Dedicated worker credentials required"));
    context(true, URL + "?password=" + secret, USER, secret)
        .run(
            c ->
                assertFailure(
                    c.getStartupFailure(), "Do not override identity through the JDBC URL"));
    context(true, URL, USER, secret)
        .run(
            c -> {
              assertNotNull(c.getStartupFailure());
              assertTrue(
                  sqlState(c.getStartupFailure(), "28P01"),
                  "Actual PostgreSQL password authentication must reject wrong credentials");
            });
    context(true, "", USER, secret)
        .run(c -> assertFailure(c.getStartupFailure(), "Use a PostgreSQL JDBC URL"));
    assertFalse(output.getAll().contains(secret));
    assertFalse(output.getAll().contains(PASSWORD));
    assertFalse(output.getAll().contains("castly-api-local-test"));
    assertEquals(
        "castly_api",
        JdbcClient.create(apiPool).sql("select current_user::text").query(String.class).single());
  }

  private void assertFailure(Throwable failure, String message) {
    assertNotNull(failure);
    boolean matched = false;
    for (Throwable t = failure; t != null; t = t.getCause())
      if (message.equals(t.getMessage())) matched = true;
    assertTrue(
        matched,
        "Startup must fail for the tested security boundary, not a missing test dependency");
  }

  private boolean sqlState(Throwable failure, String state) {
    for (Throwable t = failure; t != null; t = t.getCause())
      if (t instanceof java.sql.SQLException e && state.equals(e.getSQLState())) return true;
    return false;
  }

  @Test
  void unsafeRoleAttributesMembershipAndOwnershipFailClosed() throws Exception {
    for (String attribute :
        List.of("SUPERUSER", "BYPASSRLS", "CREATEDB", "CREATEROLE", "REPLICATION")) {
      try {
        owner("alter role " + USER + " " + attribute);
        context(false, URL, USER, PASSWORD)
            .run(
                c ->
                    assertFailure(
                        c.getStartupFailure(),
                        "Dedicated non-owner delivery worker role required"));
      } finally {
        owner("alter role " + USER + " NO" + attribute);
      }
    }
    try {
      owner("grant pg_read_all_data to " + USER);
      context(false, URL, USER, PASSWORD)
          .run(
              c ->
                  assertFailure(
                      c.getStartupFailure(), "Dedicated non-owner delivery worker role required"));
    } finally {
      owner("revoke pg_read_all_data from " + USER);
    }
    try {
      owner(
          "create table public.worker_owner_probe(id int);alter table public.worker_owner_probe"
              + " enable row level security;alter table public.worker_owner_probe owner to "
              + USER);
      context(false, URL, USER, PASSWORD)
          .run(
              c ->
                  assertFailure(
                      c.getStartupFailure(), "Dedicated non-owner delivery worker role required"));
    } finally {
      owner("drop table if exists public.worker_owner_probe");
    }
  }

  @Test
  void realPoolRecoversAfterIdleConnectionTerminationWithoutDuplicateDelivery() throws Exception {
    UUID job = queue();
    var p = provider(DeliveryProvider.Status.DELIVERED);
    try (var source = pool(USER, PASSWORD, null);
        var worker = worker(source, flags(true), p)) {
      owner(
          "select pg_terminate_backend(pid) from pg_stat_activity where application_name='"
              + source.getPoolName()
              + "'");
      recoverTick(worker, Instant.now());
      assertEquals("delivered:1", state(job));
      verify(p, times(1)).send(any());
      worker.tick(Instant.now().plusSeconds(61));
      verify(p, times(1)).send(any());
      assertEquals(
          USER,
          JdbcClient.create(source).sql("select session_user::text").query(String.class).single());
    }
  }

  @Test
  void connectionLossAfterProviderAcceptanceIsQuarantinedNotRetried() throws Exception {
    UUID job = queue();
    var sends = new AtomicInteger();
    var source = pool(USER, PASSWORD, null);
    var p = provider(DeliveryProvider.Status.DELIVERED);
    when(p.send(any()))
        .thenAnswer(
            invocation -> {
              sends.incrementAndGet();
              owner(
                  "select pg_terminate_backend(pid) from pg_stat_activity where application_name='"
                      + source.getPoolName()
                      + "'");
              return DeliveryProvider.Result.of(DeliveryProvider.Status.DELIVERED);
            });
    try (var worker = worker(source, flags(true), p)) {
      Instant now = Instant.now();
      assertThrows(RuntimeException.class, () -> worker.tick(now));
      assertEquals("sending:0", state(job));
      recoverTick(worker, now.plusSeconds(61));
      assertEquals("uncertain:0", state(job));
      assertEquals(1, sends.get());
      assertEquals(
          USER,
          JdbcClient.create(source).sql("select current_user::text").query(String.class).single());
    }
    assertTrue(source.isClosed());
  }

  private void recoverTick(EngagementWorker worker, Instant now) {
    // Physical pool validation uses real time, unlike the injected domain clock. Multiple idle
    // sockets can have died; each failed tick evicts a broken socket before a later tick recovers.
    boolean recovered = false;
    for (int n = 0; n < 3 && !recovered; n++)
      try {
        worker.tick(now);
        recovered = true;
      } catch (org.springframework.dao.DataAccessException brokenSocket) {
        /* Simulate the next scheduled invocation. */
      }
    assertTrue(recovered, "The restricted pool must recover after terminated local connections");
  }

  @Test
  void enabledFactoryUsesRestrictedLoginAndClosesItsPool() {
    var actual = new AtomicReference<HikariDataSource>();
    context(true, URL, USER, PASSWORD)
        .run(
            c -> {
              assertNull(c.getStartupFailure());
              var worker = c.getBean(EngagementWorker.class);
              var pool = (HikariDataSource) ReflectionTestUtils.getField(worker, "source");
              actual.set(pool);
              assertEquals(
                  USER,
                  JdbcClient.create(pool)
                      .sql("select usename from pg_stat_activity where pid=pg_backend_pid()")
                      .query(String.class)
                      .single());
              c.getBean(WorkerConfiguration.Tick.class).run();
              assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            });
    assertTrue(actual.get().isClosed());
  }

  @Test
  void optionalUnsubscribeControlRemainsAvailableWhileWorkerPaused() throws Exception {
    String job =
        owner(
                "insert into engagement_private.outbox(recipient_id,event_key,kind,channel)"
                    + " values('"
                    + CLIENT
                    + "','paused-unsubscribe','booking_reminder','email') returning id")
            .split("\\R")[0];
    owner(
        "update public.engagement_preferences set email_optional=true where user_id='"
            + CLIENT
            + "'");
    var f = flags(false);
    f.engagement = true;
    f.unsubscribeKey = "worker-control-fixture-key-at-least-32-characters";
    var p = provider(DeliveryProvider.Status.DELIVERED);
    try (var source = pool(USER, PASSWORD, null);
        var worker = worker(source, f, p)) {
      Instant now = Instant.now();
      worker.unsubscribe(
          new UnsubscribeTokens(f.unsubscribeKey).issue(UUID.fromString(job), now), now);
      assertEquals(
          "f",
          owner(
              "select email_optional from public.engagement_preferences where user_id='"
                  + CLIENT
                  + "'"));
      worker.tick(now);
      worker.schedule(now);
      assertEquals("queued:0", state(UUID.fromString(job)));
      assertEquals(0, count("engagement_private.scan_state"));
      verifyNoInteractions(p);
    }
  }
}
