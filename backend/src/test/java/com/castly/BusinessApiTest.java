package com.castly;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.castly.api.ActorDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.*;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/castly_spring_test",
      "spring.datasource.username=castly_api",
      "spring.datasource.password=castly-api-local-test",
      "castly.jwt.issuer=http://127.0.0.1:59999/auth/v1",
      "castly.jwt.audience=authenticated",
      "castly.jwt.mode=jwks",
      "castly.notifications.enabled=true",
      "castly.subscriptions.enabled=true",
      "castly.trust.verification-enabled=true",
      "castly.trust.reviews-enabled=true",
      "castly.google.enabled=true",
      "castly.google.checkout-enabled=true",
      "castly.google.package-name=com.castly.fixture",
      "castly.google.account-key=local-fixture-account-association-key-32-characters",
      "castly.booking-diagnostics.enabled=true",
      "castly.cors-origins=http://localhost:8081"
    })
@AutoConfigureMockMvc
class BusinessApiTest {
  @org.springframework.test.context.DynamicPropertySource
  static void prepare(org.springframework.test.context.DynamicPropertyRegistry registry) {
    try {
      int status =
          new ProcessBuilder("node", "scripts/prepare-test-db.cjs").inheritIO().start().waitFor();
      if (status != 0) throw new IllegalStateException("Local PostgreSQL preparation failed");
    } catch (Exception e) {
      throw new IllegalStateException("Integration tests require Node and local Docker", e);
    }
  }

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.castly.subscription.GooglePlayGateway google;

  @Autowired com.castly.subscription.GooglePlayService googleService;
  @Autowired com.castly.subscription.PromotionService promotionService;
  @Autowired com.castly.subscription.SubscriptionService membershipService;
  @Autowired com.castly.subscription.SubscriptionRateLimiter subscriptionLimiter;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ActorDatabase data;
  @Autowired javax.sql.DataSource pool;
  static final String CLIENT = "00000000-0000-0000-0000-000000000001",
      TALENT = "00000000-0000-0000-0000-000000000002",
      OUTSIDER = "00000000-0000-0000-0000-000000000003",
      NEW = "00000000-0000-0000-0000-000000000004";

  private ResultActions call(String method, String path, String user, String body)
      throws Exception {
    var request =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
            org.springframework.http.HttpMethod.valueOf(method), path);
    if (user != null)
      request.with(
          jwt()
              .jwt(
                  j ->
                      j.subject(user)
                          .claim("role", "authenticated")
                          .audience(List.of("authenticated"))));
    if (body != null) request.contentType("application/json").content(body);
    return mvc.perform(request);
  }

  private Map<String, Object> object(ResultActions action) throws Exception {
    return json.readValue(
        action.andReturn().getResponse().getContentAsString(),
        new com.fasterxml.jackson.core.type.TypeReference<>() {});
  }

  @Test
  void renderHealthIsPublicMinimalAndConnectionCheckIsReadOnly() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(jsonPath("$.details").doesNotExist());
    call("GET", "/api/v1/account", null, null).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    try (var connection = pool.getConnection()) {
      var result = com.castly.tools.DatabaseConnectionCheck.inspect(connection);
      assertEquals(true, result.get("role_verified"));
      assertEquals(true, result.get("private_identity_ready"));
      assertEquals(true, result.get("read_only"));
      assertEquals(true, result.get("notification_schema_ready"));
    }
    assertEquals(
        (Object) 1,
        data.as(
            UUID.fromString(CLIENT),
            db ->
                db.sql(
                        "update public.users set display_name=display_name where"
                            + " id=private.request_user_id()")
                    .update()));
    call("GET", "/api/v1/categories", null, null).andExpect(status().isOk());
  }

  @Test
  void privateEndpointsRequireAuthenticationAndCorsIsExact() throws Exception {
    call("GET", "/api/v1/account", null, null).andExpect(status().isUnauthorized());
    mvc.perform(
            options("/api/v1/account")
                .header("Origin", "http://localhost:8081")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
    mvc.perform(
            options("/api/v1/account")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
    call("GET", "/api/v1/categories", null, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5));
  }

  @Test
  void immutableOnboardingAndProtectedFields() throws Exception {
    call(
            "POST",
            "/api/v1/account/onboarding",
            NEW,
            "{\"selected_role\":\"client\",\"account_name\":\"New account\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.primary_role").value("client"));
    call(
            "POST",
            "/api/v1/account/onboarding",
            NEW,
            "{\"selected_role\":\"talent\",\"account_name\":\"Changed\"}")
        .andExpect(status().isConflict());
    call("PATCH", "/api/v1/account", CLIENT, "{\"display_name\":\"Renamed client\"}")
        .andExpect(status().isOk());
    call(
            "PATCH",
            "/api/v1/account",
            CLIENT,
            "{\"display_name\":\"Forged\",\"primary_role\":\"talent\"}")
        .andExpect(status().isBadRequest());
    call("POST", "/api/v1/bookings", CLIENT, "{\"status\":\"accepted\"}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void rlsAndPoolIdentityAndPrivilegeBoundaries() {
    UUID client = UUID.fromString(CLIENT);
    assertEquals(
        CLIENT,
        data.as(
            client,
            db -> db.sql("select private.request_user_id()::text").query(String.class).single()));
    assertEquals(
        "",
        data.as(
            null,
            db ->
                db.sql("select coalesce(private.request_user_id()::text,'')")
                    .query(String.class)
                    .single()));
    assertEquals(
        "castly_api",
        data.as(client, db -> db.sql("select current_user::text").query(String.class).single()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            data.as(
                client,
                db ->
                    db.sql(
                            "insert into public.subscription_entitlements(user_id,entitlement)"
                                + " values(private.request_user_id(),'forged')")
                        .update()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            data.as(
                client,
                db ->
                    db.sql("select public.complete_onboarding('talent','forged')")
                        .query()
                        .singleRow()));
    assertEquals(
        1,
        data.<Integer>as(
                client,
                db ->
                    db.sql("select count(*)::int from public.users").query(Integer.class).single())
            .intValue());
    assertEquals(
        0,
        data.<Integer>as(
                null,
                db ->
                    db.sql("select count(*)::int from public.users").query(Integer.class).single())
            .intValue());
  }

  @Test
  void fullMarketplaceAndBookingMessagingWorkflow() throws Exception {
    String draft =
        "{\"display_name\":\"Test"
            + " Actor\",\"category_id\":\"actors\",\"city\":\"Mumbai\",\"day_rate\":18000,\"bio\":\"Creative"
            + " profile\",\"skills\":[\"Acting\"],\"available\":true,\"published\":false,\"portrait_path\":null}";
    call("POST", "/api/v1/talents/me", CLIENT, draft).andExpect(status().isForbidden());
    call("POST", "/api/v1/talents/me", TALENT, draft).andExpect(status().isOk());
    call("GET", "/api/v1/talents?city=Mumbai", null, null)
        .andExpect(jsonPath("$.length()").value(0));
    call("GET", "/api/v1/talents/" + TALENT, OUTSIDER, null).andExpect(status().isNotFound());
    call(
            "PUT",
            "/api/v1/talents/me",
            TALENT,
            draft.replace("\"published\":false", "\"published\":true"))
        .andExpect(status().isOk());
    call("GET", "/api/v1/talents?query=Actor&city=Mumbai&minRate=10000&maxRate=20000", null, null)
        .andExpect(jsonPath("$.length()").value(1));
    call("GET", "/api/v1/talents?city=Delhi", null, null)
        .andExpect(jsonPath("$.length()").value(0));
    var asset =
        object(
            call(
                    "POST",
                    "/api/v1/talents/me/portfolio",
                    TALENT,
                    "{\"path\":\""
                        + TALENT
                        + "/photo.jpg\",\"label\":\"Portfolio photo\",\"position\":0}")
                .andExpect(status().isOk()));
    String assetId = asset.get("id").toString();
    call("DELETE", "/api/v1/portfolio/" + assetId, OUTSIDER, null).andExpect(status().isNotFound());
    call(
            "PATCH",
            "/api/v1/portfolio/" + assetId,
            TALENT,
            "{\"label\":\"Updated photo\",\"position\":1}")
        .andExpect(status().isOk());
    call("PUT", "/api/v1/favourites/" + TALENT, CLIENT, null).andExpect(status().isNoContent());
    call("GET", "/api/v1/favourites", CLIENT, null).andExpect(jsonPath("$.length()").value(1));
    call("GET", "/api/v1/favourites", OUTSIDER, null).andExpect(jsonPath("$.length()").value(0));
    call("DELETE", "/api/v1/favourites/" + TALENT, CLIENT, null).andExpect(status().isNoContent());
    String request =
        "{\"talent_id\":\""
            + TALENT
            + "\",\"project_type\":\"Editorial shoot\",\"location\":\"Mumbai"
            + " studio\",\"requirements\":\"A detailed creative project"
            + " brief\",\"proposed_date\":\""
            + java.time.LocalDate.now().plusDays(3)
            + "\",\"indicative_budget\":18000}";
    call("POST", "/api/v1/bookings", CLIENT, request.replace("18000}", "1.5}"))
        .andExpect(status().isBadRequest());
    var booking =
        object(
            call("POST", "/api/v1/bookings", CLIENT, request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("requested")));
    String id = booking.get("id").toString();
    call("GET", "/api/v1/bookings/" + id, OUTSIDER, null).andExpect(status().isNotFound());
    call("POST", "/api/v1/bookings/" + id + "/accept", CLIENT, null)
        .andExpect(status().isForbidden());
    call("POST", "/api/v1/conversations", CLIENT, "{\"booking_id\":\"" + id + "\"}")
        .andExpect(status().isConflict());
    call("POST", "/api/v1/bookings/" + id + "/accept", TALENT, null).andExpect(status().isOk());
    call("POST", "/api/v1/bookings/" + id + "/accept", TALENT, null).andExpect(status().isOk());
    var conversation =
        object(
            call("POST", "/api/v1/conversations", CLIENT, "{\"booking_id\":\"" + id + "\"}")
                .andExpect(status().isOk()));
    String conv = conversation.get("id").toString();
    call("GET", "/api/v1/conversations", TALENT, null).andExpect(jsonPath("$.length()").value(1));
    call("GET", "/api/v1/conversations/" + conv + "/messages", OUTSIDER, null)
        .andExpect(status().isNotFound());
    call("POST", "/api/v1/conversations/" + conv + "/messages", CLIENT, "{\"body\":\"\"}")
        .andExpect(status().isBadRequest());
    call(
            "POST",
            "/api/v1/conversations/" + conv + "/messages",
            CLIENT,
            "{\"body\":\"Forged\",\"sender_id\":\"" + TALENT + "\"}")
        .andExpect(status().isBadRequest());
    var first =
        object(
            call(
                    "POST",
                    "/api/v1/conversations/" + conv + "/messages",
                    CLIENT,
                    "{\"body\":\"Hello from client\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sender_id").value(CLIENT)));
    var second =
        object(
            call(
                    "POST",
                    "/api/v1/conversations/" + conv + "/messages",
                    TALENT,
                    "{\"body\":\"Hello from talent\"}")
                .andExpect(status().isCreated()));
    call(
            "GET",
            "/api/v1/conversations/" + conv + "/messages?before=" + second.get("id"),
            CLIENT,
            null)
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(first.get("id")));
    call("POST", "/api/v1/bookings/" + id + "/cancel", CLIENT, null).andExpect(status().isOk());
    call(
            "POST",
            "/api/v1/conversations/" + conv + "/messages",
            TALENT,
            "{\"body\":\"After cancellation\"}")
        .andExpect(status().isConflict());
    call("GET", "/api/v1/conversations/" + conv + "/messages", CLIENT, null)
        .andExpect(jsonPath("$.length()").value(2));
    call("POST", "/api/v1/bookings/" + id + "/accept", TALENT, null)
        .andExpect(status().isConflict());
    String another =
        object(call("POST", "/api/v1/bookings", CLIENT, request).andExpect(status().isCreated()))
            .get("id")
            .toString();
    call("POST", "/api/v1/bookings/" + another + "/reject", TALENT, null)
        .andExpect(status().isOk());
    call("POST", "/api/v1/bookings/" + another + "/cancel", CLIENT, null)
        .andExpect(status().isConflict());
    call("DELETE", "/api/v1/portfolio/" + assetId, TALENT, null).andExpect(status().isNoContent());
  }

  @Test
  void concurrentDecisionsAreSerialized() throws Exception {
    String talent = "00000000-0000-0000-0000-000000000005";
    String draft =
        "{\"display_name\":\"Race"
            + " talent\",\"category_id\":\"actors\",\"city\":\"Pune\",\"day_rate\":10000,\"bio\":\"Profile\",\"skills\":[],\"available\":true,\"published\":true,\"portrait_path\":null}";
    call("POST", "/api/v1/talents/me", talent, draft).andExpect(status().isOk());
    String request =
        "{\"talent_id\":\""
            + talent
            + "\",\"project_type\":\"Editorial\",\"location\":\"Pune\",\"requirements\":\"A"
            + " concurrent project request\",\"proposed_date\":\""
            + java.time.LocalDate.now().plusDays(2)
            + "\",\"indicative_budget\":10000}";
    String id =
        object(call("POST", "/api/v1/bookings", CLIENT, request).andExpect(status().isCreated()))
            .get("id")
            .toString();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var accept =
          pool.submit(
              () -> {
                start.await();
                return call("POST", "/api/v1/bookings/" + id + "/accept", talent, null)
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      var reject =
          pool.submit(
              () -> {
                start.await();
                return call("POST", "/api/v1/bookings/" + id + "/reject", talent, null)
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      start.countDown();
      var statuses = new ArrayList<>(List.of(accept.get(), reject.get()));
      Collections.sort(statuses);
      assertEquals(List.of(200, 409), statuses);
    }
  }

  private static final java.util.concurrent.atomic.AtomicInteger nextTalent =
      new java.util.concurrent.atomic.AtomicInteger(6);

  private String newBooking() throws Exception {
    String talent =
        "00000000-0000-0000-0000-" + String.format("%012d", nextTalent.getAndIncrement());
    call(
            "POST",
            "/api/v1/talents/me",
            talent,
            "{\"display_name\":\"Isolated"
                + " talent\",\"category_id\":\"actors\",\"city\":\"Pune\",\"day_rate\":10000,\"bio\":\"Test\",\"skills\":[],\"available\":true,\"published\":true,\"portrait_path\":null}")
        .andExpect(status().isOk());
    return object(
            call(
                    "POST",
                    "/api/v1/bookings",
                    CLIENT,
                    "{\"talent_id\":\""
                        + talent
                        + "\",\"project_type\":\"Editorial\",\"location\":\"Pune\",\"requirements\":\"An"
                        + " isolated test booking\",\"proposed_date\":\""
                        + java.time.LocalDate.now().plusDays(2)
                        + "\",\"indicative_budget\":10000}")
                .andExpect(status().isCreated()))
        .get("id")
        .toString();
  }

  private String talentFor(String booking) throws Exception {
    return object(call("GET", "/api/v1/bookings/" + booking, CLIENT, null))
        .get("talent_id")
        .toString();
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> transitionCases() {
    var cases = new ArrayList<org.junit.jupiter.params.provider.Arguments>();
    for (String state : List.of("requested", "accepted", "declined", "cancelled", "completed"))
      for (String who : List.of("client", "talent", "outsider"))
        for (String action : List.of("accept", "reject", "cancel"))
          cases.add(org.junit.jupiter.params.provider.Arguments.of(state, who, action));
    return cases.stream();
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{0}: {1} {2}")
  @org.junit.jupiter.params.provider.MethodSource("transitionCases")
  void bookingTransitionMatrix(String state, String who, String action) throws Exception {
    String id = newBooking(), talent = talentFor(id);
    if (state.equals("accepted") || state.equals("completed"))
      call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
    if (state.equals("declined"))
      call("POST", "/api/v1/bookings/" + id + "/reject", talent, null).andExpect(status().isOk());
    if (state.equals("cancelled"))
      call("POST", "/api/v1/bookings/" + id + "/cancel", CLIENT, null).andExpect(status().isOk());
    if (state.equals("completed")) {
      int exit =
          new ProcessBuilder(
                  "docker",
                  "exec",
                  "castly-spring-test-db",
                  "psql",
                  "-U",
                  "postgres",
                  "-d",
                  "castly_spring_test",
                  "-v",
                  "ON_ERROR_STOP=1",
                  "-c",
                  "update public.bookings set status='completed' where id='" + id + "'")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start()
              .waitFor();
      assertEquals(0, exit);
    }
    String user = who.equals("client") ? CLIENT : who.equals("talent") ? talent : OUTSIDER;
    String target =
        action.equals("accept") ? "accepted" : action.equals("reject") ? "declined" : "cancelled";
    int expected;
    if (who.equals("outsider")) expected = 404;
    else if (who.equals("client") && !action.equals("cancel")) expected = 403;
    else if (state.equals(target)) expected = 200;
    else if (action.equals("cancel")
        && (state.equals("accepted") || (state.equals("requested") && who.equals("client"))))
      expected = 200;
    else if (!action.equals("cancel") && state.equals("requested") && who.equals("talent"))
      expected = 200;
    else expected = 409;
    call("POST", "/api/v1/bookings/" + id + "/" + action, user, null)
        .andExpect(status().is(expected));
    call("GET", "/api/v1/bookings/" + id, CLIENT, null)
        .andExpect(jsonPath("$.status").value(expected == 200 ? target : state));
  }

  @Test
  void conversationCreationRequiresAcceptedBookingAndExplicitParticipant() throws Exception {
    String id = newBooking(), talent = talentFor(id), body = "{\"booking_id\":\"" + id + "\"}";
    call("POST", "/api/v1/conversations", CLIENT, body).andExpect(status().isConflict());
    call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
    var first =
        object(call("POST", "/api/v1/conversations", CLIENT, body).andExpect(status().isOk()));
    call("POST", "/api/v1/conversations", talent, body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(first.get("id")));
    call("POST", "/api/v1/conversations", OUTSIDER, body).andExpect(status().isNotFound());
    call("POST", "/api/v1/conversations", null, body).andExpect(status().isUnauthorized());
    call("POST", "/api/v1/conversations", CLIENT, "{\"booking_id\":\"" + UUID.randomUUID() + "\"}")
        .andExpect(status().isNotFound());
    call(
            "POST",
            "/api/v1/conversations",
            CLIENT,
            body.replace("}", ",\"client_id\":\"" + OUTSIDER + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anonymousReadsAndMissingOrMalformedTokensCannotWrite() throws Exception {
    for (String path : List.of("/api/v1/talents", "/api/v1/portfolio", "/api/v1/categories"))
      call("GET", path, null, null).andExpect(status().isOk());
    call("GET", "/api/v1/talents/me", null, null).andExpect(status().isUnauthorized());
    for (String path :
        List.of(
            "/api/v1/account/onboarding",
            "/api/v1/talents/me",
            "/api/v1/bookings",
            "/api/v1/conversations")) {
      call("POST", path, null, "{}").andExpect(status().isUnauthorized());
      mvc.perform(
              post(path)
                  .header("Authorization", "Bearer malformed-token")
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isUnauthorized())
          .andExpect(content().contentType("application/problem+json"));
    }
  }

  @Test
  void pooledClaimsAreIsolatedAcrossConcurrentActorsRollbackAndAnonymousReuse() throws Exception {
    var barrier = new java.util.concurrent.CyclicBarrier(8);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int n = 0; n < 8; n++) {
        UUID user = UUID.fromString(n % 2 == 0 ? CLIENT : TALENT);
        futures.add(
            pool.submit(
                () ->
                    data.as(
                        user,
                        db -> {
                          try {
                            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                          } catch (Exception e) {
                            throw new RuntimeException(e);
                          }
                          assertEquals(
                              user.toString(),
                              db.sql("select private.request_user_id()::text")
                                  .query(String.class)
                                  .single());
                          assertEquals(
                              user.toString(),
                              db.sql("select current_setting('request.jwt.claims')::jsonb->>'sub'")
                                  .query(String.class)
                                  .single());
                          assertEquals(
                              user.toString(),
                              db.sql("select current_setting('request.jwt.claim.sub')")
                                  .query(String.class)
                                  .single());
                          assertEquals(
                              "authenticated",
                              db.sql("select current_setting('request.jwt.claim.role')")
                                  .query(String.class)
                                  .single());
                          assertEquals(
                              user.toString(),
                              db.sql("select id::text from public.users")
                                  .query(String.class)
                                  .single());
                          return null;
                        })));
      }
      for (var future : futures) future.get(15, java.util.concurrent.TimeUnit.SECONDS);
    }
    for (int n = 0; n < 20; n++) {
      assertThrows(
          IllegalStateException.class,
          () ->
              data.as(
                  UUID.fromString(CLIENT),
                  db -> {
                    throw new IllegalStateException("rollback");
                  }));
      data.as(
          null,
          db -> {
            assertEquals(
                "",
                db.sql("select coalesce(private.request_user_id()::text,'')")
                    .query(String.class)
                    .single());
            assertEquals(
                "{}",
                db.sql("select current_setting('request.jwt.claims')")
                    .query(String.class)
                    .single());
            assertEquals(
                "anon",
                db.sql("select current_setting('request.jwt.claim.role')")
                    .query(String.class)
                    .single());
            assertEquals(
                0, db.sql("select count(*)::int from public.users").query(Integer.class).single());
            return null;
          });
    }
  }

  @Test
  void cancellationAndSendingSerializeInBothOrdersIncludingDirectApiRoleSql() throws Exception {
    for (boolean cancelFirst : List.of(true, false)) {
      String id = newBooking(), talent = talentFor(id);
      call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
      String conv =
          object(call("POST", "/api/v1/conversations", CLIENT, "{\"booking_id\":\"" + id + "\"}"))
              .get("id")
              .toString();
      var holding = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var first =
            pool.submit(
                () ->
                    data.as(
                        UUID.fromString(CLIENT),
                        db -> {
                          if (cancelFirst)
                            db.sql("update public.bookings set status='cancelled' where id=:id")
                                .param("id", UUID.fromString(id))
                                .update();
                          else
                            db.sql(
                                    "insert into public.messages(conversation_id,sender_id,body)"
                                        + " values(:conv,private.request_user_id(),'Before"
                                        + " cancellation')")
                                .param("conv", UUID.fromString(conv))
                                .update();
                          holding.countDown();
                          try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                              throw new IllegalStateException("lock timeout");
                          } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                          }
                          return null;
                        }));
        assertTrue(holding.await(10, java.util.concurrent.TimeUnit.SECONDS));
        var started = new java.util.concurrent.CountDownLatch(1);
        var second =
            pool.submit(
                () -> {
                  started.countDown();
                  return cancelFirst
                      ? call(
                              "POST",
                              "/api/v1/conversations/" + conv + "/messages",
                              talent,
                              "{\"body\":\"After cancellation\"}")
                          .andReturn()
                          .getResponse()
                          .getStatus()
                      : call("POST", "/api/v1/bookings/" + id + "/cancel", talent, null)
                          .andReturn()
                          .getResponse()
                          .getStatus();
                });
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try {
          assertThrows(
              java.util.concurrent.TimeoutException.class,
              () -> second.get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
        } finally {
          release.countDown();
        }
        first.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(
            cancelFirst ? 409 : 200, second.get(10, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      call("GET", "/api/v1/conversations/" + conv + "/messages", CLIENT, null)
          .andExpect(jsonPath("$.length()").value(cancelFirst ? 0 : 1));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              data.as(
                  UUID.fromString(CLIENT),
                  db ->
                      db.sql(
                              "insert into public.messages(conversation_id,sender_id,body)"
                                  + " values(:conv,private.request_user_id(),'Blocked direct SQL')")
                          .param("conv", UUID.fromString(conv))
                          .update()));
    }
  }

  @Test
  void anonymousPortfolioPaginationIsStableAndValidated() throws Exception {
    String booking = newBooking(), talent = talentFor(booking);
    for (String label : List.of("Page one", "Page two"))
      call(
              "POST",
              "/api/v1/talents/me/portfolio",
              talent,
              "{\"path\":\""
                  + talent
                  + "/"
                  + UUID.randomUUID()
                  + ".jpg\",\"label\":\""
                  + label
                  + "\",\"position\":0}")
          .andExpect(status().isOk());
    var first =
        call("GET", "/api/v1/portfolio?limit=1&offset=0", null, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    var second =
        call("GET", "/api/v1/portfolio?limit=1&offset=1", null, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    assertNotEquals(
        first.andReturn().getResponse().getContentAsString(),
        second.andReturn().getResponse().getContentAsString());
    call("GET", "/api/v1/portfolio?limit=1001", null, null).andExpect(status().isBadRequest());
    call("GET", "/api/v1/portfolio?offset=-1", null, null).andExpect(status().isBadRequest());
  }

  private void localOwner(String sql) throws Exception {
    var process =
        new ProcessBuilder(
                "docker",
                "exec",
                "castly-spring-test-db",
                "psql",
                "-X",
                "-U",
                "postgres",
                "-d",
                "castly_spring_test",
                "-v",
                "ON_ERROR_STOP=1",
                "-c",
                sql)
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor(), output);
  }

  @Test
  void acceptanceReusesLegacyConversationWithoutUnnecessaryInsertAndDenialRollsBack()
      throws Exception {
    String id = newBooking(), talent = talentFor(id);
    call("POST", "/api/v1/bookings/" + id + "/accept", CLIENT, null)
        .andExpect(status().isForbidden());
    call("POST", "/api/v1/bookings/" + id + "/accept", "00000000-0000-0000-0000-000000000005", null)
        .andExpect(status().isNotFound());
    localOwner("drop policy api_conversation_create on public.conversations");
    try {
      call("POST", "/api/v1/bookings/" + id + "/accept", talent, null)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.detail").value("Not permitted"))
          .andExpect(jsonPath("$.operation").value("conversation.insert"))
          .andExpect(jsonPath("$.reference").isString());
      call("GET", "/api/v1/bookings/" + id, CLIENT, null)
          .andExpect(jsonPath("$.status").value("requested"));
      assertEquals(
          (Object) 0,
          data.as(
              UUID.fromString(CLIENT),
              db ->
                  db.sql(
                          "select count(*)::int from public.notifications where booking_id=:id and"
                              + " kind='booking_accepted'")
                      .param("id", UUID.fromString(id))
                      .query(Integer.class)
                      .single()));
      localOwner(
          "insert into public.conversations(client_id,talent_id) values('"
              + CLIENT
              + "','"
              + talent
              + "')");
      call("POST", "/api/v1/bookings/" + id + "/accept", talent, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("accepted"));
      call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
      assertEquals(
          (Object) 1,
          data.as(
              UUID.fromString(CLIENT),
              db ->
                  db.sql("select count(*)::int from public.conversations where talent_id=:id")
                      .param("id", UUID.fromString(talent))
                      .query(Integer.class)
                      .single()));
      assertEquals(
          (Object) 1,
          data.as(
              UUID.fromString(CLIENT),
              db ->
                  db.sql(
                          "select count(*)::int from public.notifications where booking_id=:id and"
                              + " kind='booking_accepted'")
                      .param("id", UUID.fromString(id))
                      .query(Integer.class)
                      .single()));
    } finally {
      localOwner(
          "create policy api_conversation_create on public.conversations for insert to castly_api"
              + " with check((client_id=(select private.request_user_id()) or talent_id=(select"
              + " private.request_user_id())) and exists(select 1 from public.bookings b where"
              + " b.client_id=conversations.client_id and b.talent_id=conversations.talent_id and"
              + " b.status='accepted'))");
    }
  }

  @Test
  void notificationCutoffDoesNotMarkNewerMessagesAndRejectsForeignMessageCursor() throws Exception {
    String id = newBooking(), talent = talentFor(id);
    call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
    String conv =
        object(call("POST", "/api/v1/conversations", CLIENT, "{\"booking_id\":\"" + id + "\"}"))
            .get("id")
            .toString();
    String first =
        object(
                call(
                        "POST",
                        "/api/v1/conversations/" + conv + "/messages",
                        CLIENT,
                        "{\"body\":\"First notice\"}")
                    .andExpect(status().isCreated()))
            .get("id")
            .toString();
    call(
            "POST",
            "/api/v1/conversations/" + conv + "/messages",
            CLIENT,
            "{\"body\":\"Newer notice\"}")
        .andExpect(status().isCreated());
    call(
            "POST",
            "/api/v1/conversations/" + conv + "/notifications/read?through=" + first,
            talent,
            null)
        .andExpect(status().isNoContent());
    call("GET", "/api/v1/conversations/" + conv, talent, null)
        .andExpect(jsonPath("$.unread_notifications").value(1));
    call(
            "POST",
            "/api/v1/conversations/" + conv + "/notifications/read?through=" + UUID.randomUUID(),
            talent,
            null)
        .andExpect(status().isNotFound());
    call(
            "POST",
            "/api/v1/conversations/" + conv + "/notifications/read?through=" + first,
            OUTSIDER,
            null)
        .andExpect(status().isNotFound());
  }

  @Test
  void notificationInvokersKeepConcurrentActorsIsolatedAndMessageRollbackAtomic() throws Exception {
    var pairs = new ArrayList<String[]>();
    for (int n = 0; n < 4; n++) {
      String id = newBooking(), talent = talentFor(id);
      call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
      String conv =
          object(call("POST", "/api/v1/conversations", CLIENT, "{\"booking_id\":\"" + id + "\"}"))
              .get("id")
              .toString();
      pairs.add(new String[] {conv, talent});
    }
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      var start = new java.util.concurrent.CountDownLatch(1);
      for (var pair : pairs)
        for (String sender : List.of(CLIENT, pair[1]))
          futures.add(
              executor.submit(
                  () -> {
                    try {
                      assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                      String message =
                          object(
                                  call(
                                          "POST",
                                          "/api/v1/conversations/" + pair[0] + "/messages",
                                          sender,
                                          "{\"body\":\"Concurrent private notice\"}")
                                      .andExpect(status().isCreated()))
                              .get("id")
                              .toString();
                      String recipient = sender.equals(CLIENT) ? pair[1] : CLIENT;
                      assertEquals(
                          (Object) 1,
                          data.as(
                              UUID.fromString(recipient),
                              db ->
                                  db.sql(
                                          "select count(*)::int from public.notifications where"
                                              + " message_id=:id")
                                      .param("id", UUID.fromString(message))
                                      .query(Integer.class)
                                      .single()));
                      assertEquals(
                          (Object) 0,
                          data.as(
                              UUID.fromString(sender),
                              db ->
                                  db.sql(
                                          "select count(*)::int from public.notifications where"
                                              + " message_id=:id")
                                      .param("id", UUID.fromString(message))
                                      .query(Integer.class)
                                      .single()));
                      assertEquals(
                          (Object) 0,
                          data.as(
                              null,
                              db ->
                                  db.sql(
                                          "select count(*)::int from public.notifications where"
                                              + " message_id=:id")
                                      .param("id", UUID.fromString(message))
                                      .query(Integer.class)
                                      .single()));
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  }));
      start.countDown();
      for (var future : futures) future.get(15, java.util.concurrent.TimeUnit.SECONDS);
    }
    var pair = pairs.get(0);
    int before =
        data.as(
            UUID.fromString(pair[1]),
            db ->
                db.sql("select count(*)::int from public.notifications where conversation_id=:id")
                    .param("id", UUID.fromString(pair[0]))
                    .query(Integer.class)
                    .single());
    assertThrows(
        IllegalStateException.class,
        () ->
            data.as(
                UUID.fromString(CLIENT),
                db -> {
                  db.sql(
                          "insert into public.messages(conversation_id,sender_id,body)"
                              + " values(:id,private.request_user_id(),'Rolled back message')")
                      .param("id", UUID.fromString(pair[0]))
                      .update();
                  throw new IllegalStateException("Synthetic rollback");
                }));
    assertEquals(
        (Object) before,
        data.as(
            UUID.fromString(pair[1]),
            db ->
                db.sql("select count(*)::int from public.notifications where conversation_id=:id")
                    .param("id", UUID.fromString(pair[0]))
                    .query(Integer.class)
                    .single()));
    assertEquals(
        (Object) 0,
        data.as(
            UUID.fromString(CLIENT),
            db ->
                db.sql(
                        "select count(*)::int from public.messages where conversation_id=:id and"
                            + " body='Rolled back message'")
                    .param("id", UUID.fromString(pair[0]))
                    .query(Integer.class)
                    .single()));
  }

  @Test
  void notificationsAreTransactionalRecipientOnlyDeduplicatedAndNotReadReceipts() throws Exception {
    String id = newBooking(), talent = talentFor(id);
    var notification =
        data.as(
            UUID.fromString(talent),
            db ->
                com.castly.api.ActorDatabase.one(
                    db.sql(
                            "select id from public.notifications where booking_id=:id and"
                                + " kind='booking_requested'")
                        .param("id", UUID.fromString(id))));
    String notice = notification.get("id").toString();
    call("GET", "/api/v1/notifications", null, null).andExpect(status().isUnauthorized());
    call("POST", "/api/v1/notifications/" + notice + "/read", OUTSIDER, null)
        .andExpect(status().isNotFound());
    call("POST", "/api/v1/notifications/" + notice + "/read", talent, null)
        .andExpect(status().isNoContent());
    call("POST", "/api/v1/notifications/" + notice + "/read", talent, null)
        .andExpect(status().isNoContent());
    call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
    var conversation =
        object(call("POST", "/api/v1/conversations", CLIENT, "{\"booking_id\":\"" + id + "\"}"));
    String conv = conversation.get("id").toString();
    call(
            "POST",
            "/api/v1/conversations/" + conv + "/messages",
            CLIENT,
            "{\"body\":\"Notification test message\"}")
        .andExpect(status().isCreated());
    call("GET", "/api/v1/conversations/" + conv, talent, null)
        .andExpect(jsonPath("$.unread_notifications").value(1));
    call("GET", "/api/v1/notifications/unread-count", talent, null)
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.message_unread_count").value(1));
    call("GET", "/api/v1/conversations/" + conv, CLIENT, null)
        .andExpect(jsonPath("$.unread_notifications").value(0));
    call("POST", "/api/v1/conversations/" + conv + "/notifications/read", OUTSIDER, null)
        .andExpect(status().isNotFound());
    call("POST", "/api/v1/conversations/" + conv + "/notifications/read", talent, null)
        .andExpect(status().isNoContent());
    call("GET", "/api/v1/conversations/" + conv, talent, null)
        .andExpect(jsonPath("$.unread_notifications").value(0));
    call("GET", "/api/v1/conversations/" + conv + "/messages", CLIENT, null)
        .andExpect(jsonPath("$[0].read_at").doesNotExist());
    call("GET", "/api/v1/notifications?limit=1", talent, null)
        .andExpect(jsonPath("$.length()").value(1));
    call("GET", "/api/v1/notifications?before=" + notice, OUTSIDER, null)
        .andExpect(status().isNotFound());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            data.as(
                UUID.fromString(talent),
                db ->
                    db.sql(
                            "insert into"
                                + " public.notifications(recipient_id,kind,event_key,booking_id)"
                                + " values(private.request_user_id(),'booking_accepted','forged',:id)")
                        .param("id", UUID.fromString(id))
                        .update()));
    var disabled =
        new com.castly.api.NotificationController(
            data, new com.castly.api.NotificationService(false));
    var jwt =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture")
            .header("alg", "RS256")
            .subject(CLIENT)
            .build();
    assertEquals(Map.of("enabled", false), disabled.unread(jwt));
  }

  @Test
  void rejectionAndCancellationNotifyOnlyTheCounterpartyOnce() throws Exception {
    for (String action : List.of("reject", "cancel")) {
      String id = newBooking(),
          talent = talentFor(id),
          actor = action.equals("reject") ? talent : CLIENT;
      call("POST", "/api/v1/bookings/" + id + "/" + action, actor, null).andExpect(status().isOk());
      call("POST", "/api/v1/bookings/" + id + "/" + action, actor, null).andExpect(status().isOk());
      UUID recipient = UUID.fromString(action.equals("reject") ? CLIENT : talent);
      assertEquals(
          (Object) 1,
          data.as(
              recipient,
              db ->
                  db.sql(
                          "select count(*)::int from public.notifications where booking_id=:id and"
                              + " kind=:kind")
                      .param("id", UUID.fromString(id))
                      .param(
                          "kind",
                          action.equals("reject") ? "booking_declined" : "booking_cancelled")
                      .query(Integer.class)
                      .single()));
    }
  }

  @Test
  void acceptanceNeedsOnlyConversationColumnInsertGrantsAndTriggerExecuteIsNotRequired()
      throws Exception {
    String id = newBooking(), talent = talentFor(id);
    data.as(
        UUID.fromString(talent),
        db -> {
          assertFalse(
              db.sql("select has_table_privilege(current_user,'public.conversations','INSERT')")
                  .query(Boolean.class)
                  .single());
          assertTrue(
              db.sql(
                      "select"
                          + " has_column_privilege(current_user,'public.conversations','client_id','INSERT')"
                          + " and has_column_privilege(current_user,'public.conversations','talent_id','INSERT')")
                  .query(Boolean.class)
                  .single());
          assertFalse(
              db.sql(
                      "select"
                          + " has_function_privilege(current_user,'public.api_booking_transition_guard()','EXECUTE')")
                  .query(Boolean.class)
                  .single());
          return null;
        });
    call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
    call("POST", "/api/v1/conversations", talent, "{\"booking_id\":\"" + id + "\"}")
        .andExpect(status().isOk());
  }

  @Test
  void bothInvokerBookingTriggersWorkWithOnlyStatusUpdateAndNoTriggerExecuteGrant()
      throws Exception {
    String id = newBooking(), talent = talentFor(id);
    data.as(
        UUID.fromString(talent),
        db -> {
          assertFalse(
              db.sql("select has_table_privilege(current_user,'public.bookings','UPDATE')")
                  .query(Boolean.class)
                  .single());
          assertTrue(
              db.sql(
                      "select"
                          + " has_column_privilege(current_user,'public.bookings','status','UPDATE')")
                  .query(Boolean.class)
                  .single());
          assertFalse(
              db.sql(
                      "select"
                          + " has_column_privilege(current_user,'public.bookings','updated_at','UPDATE')")
                  .query(Boolean.class)
                  .single());
          for (String function :
              List.of("public.api_booking_transition_guard()", "public.stamp_updated_at()")) {
            var metadata =
                db.sql(
                        "select prosecdef,has_function_privilege(current_user,oid,'EXECUTE') as"
                            + " can_execute from pg_proc where oid=cast(:function as regprocedure)")
                    .param("function", function)
                    .query()
                    .singleRow();
            assertEquals(false, metadata.get("prosecdef"));
            assertEquals(false, metadata.get("can_execute"));
          }
          return null;
        });
    // Uses the real SELECT FOR UPDATE -> UPDATE(status) RETURNING * -> conversation flow.
    call("POST", "/api/v1/bookings/" + id + "/accept", talent, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("accepted"))
        .andExpect(jsonPath("$.updated_at").isNotEmpty());
  }

  @Test
  void invokerGuardFailsClosedWhenActorAccountIsHiddenByUsersRls() throws Exception {
    String id = newBooking(), talent = talentFor(id);
    // Disposable local fixture only: reproduce the guard's role lookup returning no row.
    localOwner(
        "create policy test_hide_guard_account on public.users as restrictive for select to"
            + " castly_api using(false)");
    try {
      var evidence =
          data.as(
              UUID.fromString(talent),
              db ->
                  new com.castly.api.BookingUpdateDiagnostics(true)
                      .capture(
                          db,
                          UUID.fromString(talent),
                          ActorDatabase.one(
                              db.sql("select * from public.bookings where id=:id")
                                  .param("id", UUID.fromString(id)))));
      assertEquals("ok", evidence.probe());
      assertEquals(true, evidence.identityMatches());
      assertEquals(false, evidence.accountVisible());
      assertEquals(false, evidence.roleMatches());
      var failure =
          assertThrows(
              org.springframework.dao.DataAccessException.class,
              () ->
                  data.as(
                      UUID.fromString(talent),
                      db ->
                          db.sql(
                                  "update public.bookings set status='accepted' where id=:id"
                                      + " returning *")
                              .param("id", UUID.fromString(id))
                              .query()
                              .singleRow()));
      var sql = assertInstanceOf(java.sql.SQLException.class, failure.getMostSpecificCause());
      assertEquals("42501", sql.getSQLState());
      assertTrue(sql.getMessage().contains("Booking account role mismatch"));
      assertTrue(sql.getMessage().contains("api_booking_transition_guard"));
      call("POST", "/api/v1/bookings/" + id + "/accept", talent, null)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.detail").value("Not permitted"))
          .andExpect(jsonPath("$.operation").value("booking.accepted.update"))
          .andExpect(jsonPath("$.reason").doesNotExist());
      call("GET", "/api/v1/bookings/" + id, talent, null)
          .andExpect(jsonPath("$.status").value("requested"));
    } finally {
      localOwner("drop policy test_hide_guard_account on public.users");
    }
    call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
  }

  @Test
  void diagnosticProbeVerifiesTalentInSameTransactionAndRecoversFromItsOwnPermissionError()
      throws Exception {
    String id = newBooking(), talent = talentFor(id);
    data.as(
        UUID.fromString(talent),
        db -> {
          String transaction = db.sql("select txid_current()::text").query(String.class).single();
          var booking =
              ActorDatabase.one(
                  db.sql("select * from public.bookings where id=:id")
                      .param("id", UUID.fromString(id)));
          var diagnostics = new com.castly.api.BookingUpdateDiagnostics(true);
          var evidence = diagnostics.capture(db, UUID.fromString(talent), booking);
          assertEquals("ok", evidence.probe());
          assertEquals(true, evidence.identityPresent());
          assertEquals(true, evidence.identityMatches());
          assertEquals(true, evidence.accountVisible());
          assertEquals(true, evidence.roleMatches());
          var mismatch = diagnostics.capture(db, UUID.fromString(CLIENT), booking);
          assertEquals(false, mismatch.identityMatches());
          assertEquals(false, mismatch.roleMatches());
          db.sql("update public.bookings set status='accepted' where id=:id")
              .param("id", UUID.fromString(id))
              .update();
          assertEquals(
              transaction, db.sql("select txid_current()::text").query(String.class).single());
          return null;
        });
    String denied = newBooking();
    String deniedTalent = talentFor(denied);
    localOwner("revoke select on public.users from castly_api");
    try {
      data.as(
          UUID.fromString(deniedTalent),
          db -> {
            var booking =
                ActorDatabase.one(
                    db.sql("select * from public.bookings where id=:id")
                        .param("id", UUID.fromString(denied)));
            var evidence =
                new com.castly.api.BookingUpdateDiagnostics(true)
                    .capture(db, UUID.fromString(deniedTalent), booking);
            assertEquals("postgres_privilege_denied", evidence.probe());
            assertEquals(true, evidence.identityPresent());
            assertEquals(true, evidence.identityMatches());
            assertNull(evidence.accountVisible());
            assertNull(evidence.roleMatches());
            // If the probe left this transaction aborted, even this read would fail with 25P02.
            assertEquals(1, db.sql("select 1").query(Integer.class).single());
            return null;
          });
      call("POST", "/api/v1/bookings/" + denied + "/accept", deniedTalent, null)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.operation").value("booking.accepted.update"));
    } finally {
      localOwner("grant select on public.users to castly_api");
    }
    call("POST", "/api/v1/bookings/" + denied + "/accept", deniedTalent, null)
        .andExpect(status().isOk());
  }

  @Test
  void privateIdentityFallbackWorksWithoutAuthUsageAndKeepsLeastPrivilegeIsolation()
      throws Exception {
    String id = newBooking(), talent = talentFor(id);
    var evidence =
        data.as(
            UUID.fromString(talent),
            db -> {
              assertFalse(
                  db.sql("select has_schema_privilege(current_user,'auth','USAGE')")
                      .query(Boolean.class)
                      .single());
              assertTrue(
                  db.sql("select has_schema_privilege(current_user,'private','USAGE')")
                      .query(Boolean.class)
                      .single());
              assertFalse(
                  db.sql(
                          "select has_schema_privilege(current_user,'private','CREATE')"
                              + " or has_schema_privilege('authenticated','private','USAGE')"
                              + " or has_schema_privilege('anon','private','USAGE')")
                      .query(Boolean.class)
                      .single());
              assertFalse(
                  db.sql(
                          "select prosecdef from pg_proc where oid="
                              + " 'private.request_user_id()'::regprocedure")
                      .query(Boolean.class)
                      .single());
              assertTrue(
                  db.sql(
                          "select provolatile='s' and proconfig=array['search_path=\"\"']"
                              + " from pg_proc where oid='private.request_user_id()'::regprocedure")
                      .query(Boolean.class)
                      .single());
              assertTrue(
                  db.sql(
                          "select has_function_privilege(current_user,p.oid,'EXECUTE') and"
                              + " not has_function_privilege('authenticated',p.oid,'EXECUTE') and"
                              + " not has_function_privilege('anon',p.oid,'EXECUTE') from pg_proc p"
                              + " where oid='private.request_user_id()'::regprocedure")
                      .query(Boolean.class)
                      .single());
              assertFalse(
                  db.sql(
                          "select rolsuper or rolbypassrls or exists(select 1 from pg_auth_members"
                              + " where member=r.oid) from pg_roles r where rolname=current_user")
                      .query(Boolean.class)
                      .single());
              var row =
                  ActorDatabase.one(
                      db.sql("select * from public.bookings where id=:id for update")
                          .param("id", UUID.fromString(id)));
              return new com.castly.api.BookingUpdateDiagnostics(true)
                  .capture(db, UUID.fromString(talent), row);
            });
    var denied =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () ->
                data.as(
                    UUID.fromString(talent),
                    db -> db.sql("select auth.uid()").query().singleRow()));
    assertEquals("42501", com.castly.api.SqlFailureKind.sqlState(denied));
    assertEquals("ok", evidence.probe());
    assertEquals(true, evidence.identityMatches());
    assertEquals(true, evidence.accountVisible());
    assertEquals(true, evidence.roleMatches());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/bookings/" + id + "/accept")
                .header("Authorization", "Bearer invalid-jwt"))
        .andExpect(status().isUnauthorized());
    call("POST", "/api/v1/bookings/" + id + "/accept", CLIENT, null)
        .andExpect(status().isForbidden());
    call("POST", "/api/v1/bookings/" + id + "/accept", "00000000-0000-0000-0000-000000000005", null)
        .andExpect(status().isNotFound());
    call("POST", "/api/v1/bookings/" + id + "/accept", talent, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("accepted"));
    call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
    var conversation =
        object(
            call("POST", "/api/v1/conversations", CLIENT, "{\"booking_id\":\"" + id + "\"}")
                .andExpect(status().isOk()));
    String path = "/api/v1/conversations/" + conversation.get("id");
    call("GET", path, OUTSIDER, null).andExpect(status().isNotFound());
    call("POST", path + "/messages", OUTSIDER, "{\"body\":\"Unauthorized\"}")
        .andExpect(status().isNotFound());
    call("POST", path + "/messages", talent, "{\"body\":\"Accepted and authorized\"}")
        .andExpect(status().isCreated());
    data.as(
        UUID.fromString(talent),
        db -> {
          assertEquals(
              0,
              db.sql("select count(*)::int from public.users where id=:client")
                  .param("client", UUID.fromString(CLIENT))
                  .query(Integer.class)
                  .single());
          assertEquals(
              1,
              db.sql("select count(*)::int from public.conversations where talent_id=:talent")
                  .param("talent", UUID.fromString(talent))
                  .query(Integer.class)
                  .single());
          assertFalse(
              db.sql("select has_table_privilege(current_user,'public.bookings','UPDATE')")
                  .query(Boolean.class)
                  .single());
          return null;
        });
    var authUsers =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () ->
                data.as(
                    UUID.fromString(talent),
                    db -> db.sql("select id from auth.users").query().listOfRows()));
    assertEquals("42501", com.castly.api.SqlFailureKind.sqlState(authUsers));
  }

  @Test
  void privateIdentitySettingsDoNotOutliveCommitOrRollback() {
    var raw = org.springframework.jdbc.core.simple.JdbcClient.create(pool);
    for (String actor : List.of(CLIENT, TALENT)) {
      assertEquals(
          actor,
          data.as(
              UUID.fromString(actor),
              db -> db.sql("select private.request_user_id()::text").query(String.class).single()));
      assertNull(
          raw.sql("select private.request_user_id()::text")
              .query(String.class)
              .optional()
              .orElse(null));
      assertThrows(
          IllegalStateException.class,
          () ->
              data.as(
                  UUID.fromString(actor),
                  db -> {
                    assertEquals(
                        actor,
                        db.sql("select private.request_user_id()::text")
                            .query(String.class)
                            .single());
                    throw new IllegalStateException("Synthetic rollback");
                  }));
      assertNull(
          raw.sql("select private.request_user_id()::text")
              .query(String.class)
              .optional()
              .orElse(null));
    }
    var membership =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () ->
                data.as(
                    UUID.fromString(CLIENT),
                    db -> db.sql("set local role authenticated").update()));
    assertEquals("42501", com.castly.api.SqlFailureKind.sqlState(membership));
  }

  @Test
  void privateIdentityReadsBothClaimFormatsAndRejectsMalformedOrConflictingContexts() {
    record Case(String scalar, String claims, String expected, boolean denied) {}
    for (var sample :
        List.of(
            new Case("", "", null, false),
            new Case("", "{}", null, false),
            new Case(CLIENT, "", CLIENT, false),
            new Case("", "{\"sub\":\"" + TALENT + "\"}", TALENT, false),
            new Case(CLIENT, "{\"sub\":\"" + CLIENT + "\"}", CLIENT, false),
            new Case(CLIENT, "{\"sub\":\"" + TALENT + "\"}", null, true),
            new Case("bad-uuid", "", null, true),
            new Case("", "{bad-json", null, true),
            new Case(CLIENT, "{bad-json", null, true),
            new Case("", "{\"sub\":123}", null, true),
            new Case("", "[]", null, true))) {
      java.util.function.Supplier<String> run =
          () ->
              data.as(
                  null,
                  db -> {
                    db.sql(
                            "select set_config('request.jwt.claim.sub',:scalar,true),"
                                + "set_config('request.jwt.claims',:claims,true)")
                        .param("scalar", sample.scalar())
                        .param("claims", sample.claims())
                        .query()
                        .singleRow();
                    return db.sql("select private.request_user_id()::text")
                        .query(String.class)
                        .optional()
                        .orElse(null);
                  });
      if (sample.denied()) {
        var failure = assertThrows(org.springframework.dao.DataAccessException.class, run::get);
        assertEquals("42501", com.castly.api.SqlFailureKind.sqlState(failure));
        assertTrue(
            failure
                .getMostSpecificCause()
                .getMessage()
                .contains("Invalid request identity context"));
      } else assertEquals(sample.expected(), run.get());
      assertNull(
          data.as(
              null,
              db ->
                  db.sql("select private.request_user_id()::text")
                      .query(String.class)
                      .optional()
                      .orElse(null)));
      assertEquals(
          (Object) 0,
          data.as(
              null,
              db ->
                  db.sql("select count(*)::int from public.bookings")
                      .query(Integer.class)
                      .single()));
    }
  }

  @Test
  void identityMigrationPreservesPolicyPredicatesScopesAndGuardAuthorizationBodies()
      throws Exception {
    String variant = System.getenv().getOrDefault("CASTLY_TEST_SCHEMA_PATH", "repository");
    var before =
        json.readTree(
            java.nio.file.Files.readString(
                java.nio.file.Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "castly-identity-before-" + variant + ".json")));
    String afterJson =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"),
                "castly-identity-after-" + variant + ".json"));
    var after = json.readTree(afterJson);
    var expected = before.deepCopy();
    for (var policy : expected.get("policies")) {
      if (policy.get("roles").size() == 1
          && "castly_api".equals(policy.get("roles").get(0).asText())) {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) policy;
        for (String field : List.of("qual", "with_check"))
          if (!node.get(field).isNull())
            node.put(
                field, node.get(field).asText().replace("auth.uid()", "private.request_user_id()"));
      }
    }
    for (var guard : expected.get("guards")) {
      var node = (com.fasterxml.jackson.databind.node.ObjectNode) guard;
      node.put(
          "body", node.get("body").asText().replace("auth.uid()", "private.request_user_id()"));
    }
    assertEquals(
        expected,
        after,
        "Only API identity calls may change; roles/checks/guards/ACLs stay identical");
  }

  @Test
  void permissionDiagnosticsDistinguishBookingLockFromNewRowRlsUpdateFailure() throws Exception {
    String id = newBooking(), talent = talentFor(id);
    localOwner("revoke update(status) on public.bookings from castly_api");
    try {
      call("POST", "/api/v1/bookings/" + id + "/accept", talent, null)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.operation").value("booking.accepted.lock"));
    } finally {
      localOwner("grant update(status) on public.bookings to castly_api");
    }
    localOwner(
        "create policy test_restrict_booking on public.bookings as restrictive for update to"
            + " castly_api using(true) with check(status='requested')");
    try {
      call("POST", "/api/v1/bookings/" + id + "/accept", talent, null)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.operation").value("booking.accepted.update"));
      call("GET", "/api/v1/bookings/" + id, CLIENT, null)
          .andExpect(jsonPath("$.status").value("requested"));
    } finally {
      localOwner("drop policy test_restrict_booking on public.bookings");
    }
    call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
  }

  @Test
  void subscriptionCatalogIsPublicAndMembershipIsPrivate() throws Exception {
    call("GET", "/api/v1/subscriptions/plans", null, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("free"))
        .andExpect(jsonPath("$[1].google_product_id").isEmpty());
    call("GET", "/api/v1/subscriptions/me", null, null).andExpect(status().isUnauthorized());
    call("GET", "/api/v1/subscriptions/me", TALENT, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_creator_pro").value(false));
    call("GET", "/api/v1/subscriptions/history?limit=101", TALENT, null)
        .andExpect(status().isBadRequest());
    call("GET", "/api/v1/subscriptions/history", TALENT, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void subscriptionLedgerDeniesUnverifiedSelfGrant() {
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            data.as(
                UUID.fromString(TALENT),
                db ->
                    db.sql(
                            "insert into"
                                + " public.subscription_grants(user_id,entitlement,source,source_key)"
                                + " values(:u,'creator_pro','google_play','forged')")
                        .param("u", UUID.fromString(TALENT))
                        .update()));
    assertEquals(
        (Object) 0,
        data.as(
            UUID.fromString(OUTSIDER),
            db ->
                db.sql("select * from public.subscription_grants where user_id=:u")
                    .param("u", UUID.fromString(TALENT))
                    .query()
                    .listOfRows()
                    .size()));
  }

  @Test
  void foundingOfferRequiresCreatorAndTrustedReviewerAndGrantsOnce() throws Exception {
    call(
            "POST",
            "/api/v1/subscriptions/promotions/claims",
            CLIENT,
            "{\"post_url\":\"https://instagram.com/reel/client\"}")
        .andExpect(status().isForbidden());
    call(
            "POST",
            "/api/v1/subscriptions/promotions/claims",
            TALENT,
            "{\"post_url\":\"https://localhost/private\"}")
        .andExpect(status().isBadRequest());
    var claim =
        object(
            call(
                    "POST",
                    "/api/v1/subscriptions/promotions/claims",
                    TALENT,
                    "{\"post_url\":\"https://www.instagram.com/reel/founding-test/?tracking=1\"}")
                .andExpect(status().isOk()));
    String id = claim.get("id").toString();
    call(
            "POST",
            "/api/v1/subscriptions/promotions/claims",
            TALENT,
            "{\"post_url\":\"https://instagram.com/reel/founding-test\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
    call("GET", "/api/v1/admin/subscriptions/promotions", TALENT, null)
        .andExpect(status().isForbidden());
    call(
            "POST",
            "/api/v1/admin/subscriptions/promotions/" + id + "/approve",
            OUTSIDER,
            "{\"reason\":\"Verified public creator video\"}")
        .andExpect(status().isForbidden());
    var process =
        new ProcessBuilder(
                "docker",
                "exec",
                "castly-spring-test-db",
                "psql",
                "-U",
                "postgres",
                "-d",
                "castly_spring_test",
                "-v",
                "ON_ERROR_STOP=1",
                "-c",
                "insert into private.subscription_admins values ('"
                    + OUTSIDER
                    + "') on conflict do nothing")
            .start();
    assertEquals(0, process.waitFor());
    try {
      call("GET", "/api/v1/admin/subscriptions/promotions", OUTSIDER, null)
          .andExpect(status().isOk());
      for (int n = 0; n < 2; n++)
        call(
                "POST",
                "/api/v1/admin/subscriptions/promotions/" + id + "/approve",
                OUTSIDER,
                "{\"reason\":\"Verified public creator video\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("approved"));
      call(
              "POST",
              "/api/v1/admin/subscriptions/promotions/" + id + "/reject",
              OUTSIDER,
              "{\"reason\":\"Cannot reverse\"}")
          .andExpect(status().isConflict());
      call("GET", "/api/v1/subscriptions/me", TALENT, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.is_creator_pro").value(true));
      assertEquals(
          (Object) 1L,
          data.as(
              UUID.fromString(TALENT),
              db ->
                  db.sql("select count(*) from public.subscription_grants where source_key=:key")
                      .param("key", "promotion:" + id)
                      .query(Long.class)
                      .single()));
      call("GET", "/api/v1/subscriptions/promotions/me", CLIENT, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    } finally {
      var cleanup =
          new ProcessBuilder(
                  "docker",
                  "exec",
                  "castly-spring-test-db",
                  "psql",
                  "-U",
                  "postgres",
                  "-d",
                  "castly_spring_test",
                  "-c",
                  "delete from public.subscription_audit_logs where resource_id='"
                      + id
                      + "'; delete from public.subscription_grants where source_key='promotion:"
                      + id
                      + "'; delete from public.subscription_entitlements where user_id='"
                      + TALENT
                      + "'; delete from public.promotion_claims where id='"
                      + id
                      + "'; delete from private.subscription_admins where user_id='"
                      + OUTSIDER
                      + "'")
              .start();
      assertEquals(0, cleanup.waitFor());
    }
  }

  private void ownerFixture(String sql) throws Exception {
    var process =
        new ProcessBuilder(
                "docker",
                "exec",
                "castly-spring-test-db",
                "psql",
                "-U",
                "postgres",
                "-d",
                "castly_spring_test",
                "-v",
                "ON_ERROR_STOP=1",
                "-c",
                sql)
            .start();
    assertEquals(0, process.waitFor(), new String(process.getErrorStream().readAllBytes()));
  }

  @Test
  void googleLifecycleIsAuthoritativeIdempotentAndReplaySafe() throws Exception {
    ownerFixture(
        "update public.subscription_plans set"
            + " google_product_id='creator_pro_fixture',google_base_plan_id='annual' where"
            + " id='creator_pro'");
    org.mockito.Mockito.when(google.configured()).thenReturn(true);
    String token = "fixture-purchase-token-123456789";
    String hash = com.castly.subscription.GooglePlayService.hash(token);
    var state = new java.util.concurrent.atomic.AtomicReference<>("ACTIVE");
    org.mockito.Mockito.when(google.get("com.castly.fixture", token))
        .thenAnswer(
            invocation ->
                json.readTree(
                    "{\"subscriptionState\":\"SUBSCRIPTION_STATE_"
                        + state.get()
                        + "\",\"acknowledgementState\":\"ACKNOWLEDGEMENT_STATE_PENDING\",\"externalAccountIdentifiers\":{\"obfuscatedExternalAccountId\":\""
                        + googleService.accountId(UUID.fromString(TALENT))
                        + "\"},\"lineItems\":[{\"productId\":\"creator_pro_fixture\",\"offerDetails\":{\"basePlanId\":\"annual\"},\"expiryTime\":\"2030-11-09T00:00:00Z\",\"latestSuccessfulOrderId\":\"GPA.fixture.123\",\"autoRenewingPlan\":{\"autoRenewEnabled\":true}}]}"));
    String body = json.writeValueAsString(Map.of("purchase_token", token));
    try {
      call("GET", "/api/v1/subscriptions/google-play/configuration", TALENT, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.enabled").value(true));
      var pausedCheckout =
          new com.castly.subscription.GooglePlayService(
              membershipService,
              subscriptionLimiter,
              google,
              json,
              true,
              false,
              "com.castly.fixture",
              "local-fixture-account-association-key-32-characters");
      assertEquals(
          false, pausedCheckout.configuration(UUID.fromString(TALENT)).get("checkout_enabled"));
      assertEquals(true, pausedCheckout.configuration(UUID.fromString(TALENT)).get("enabled"));
      assertEquals(
          true, pausedCheckout.verify(UUID.fromString(TALENT), token).get("is_creator_pro"));
      for (int n = 0; n < 2; n++)
        call("POST", "/api/v1/subscriptions/google-play/verify", TALENT, body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.is_creator_pro").value(true));
      assertEquals(
          (Object) 1L,
          data.as(
              UUID.fromString(TALENT),
              db ->
                  db.sql("select count(*) from public.subscription_orders where token_hash=:h")
                      .param("h", hash)
                      .query(Long.class)
                      .single()));
      call("POST", "/api/v1/subscriptions/google-play/verify", OUTSIDER, body)
          .andExpect(status().isForbidden());
      call(
              "POST",
              "/api/v1/subscriptions/google-play/verify",
              "00000000-0000-0000-0000-000000000005",
              body)
          .andExpect(status().isConflict());
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              data.as(
                  UUID.fromString(OUTSIDER),
                  db ->
                      db.sql(
                              "insert into"
                                  + " public.subscription_grants(user_id,entitlement,source,source_key)"
                                  + " values(:u,'creator_pro','google_play','forged-google')")
                          .param("u", UUID.fromString(OUTSIDER))
                          .update()));
      assertEquals(
          (Object) 0,
          data.as(
              UUID.fromString(TALENT),
              db ->
                  db.sql(
                          "update public.subscription_orders set state='revoked' where"
                              + " token_hash=:h")
                      .param("h", hash)
                      .update()));
      state.set("ON_HOLD");
      call("POST", "/api/v1/subscriptions/google-play/restore", TALENT, body)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.is_creator_pro").value(false));
      state.set("IN_GRACE_PERIOD");
      call("POST", "/api/v1/subscriptions/google-play/restore", TALENT, body)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.is_creator_pro").value(true));
      state.set("CANCELED");
      call("POST", "/api/v1/subscriptions/google-play/restore", TALENT, body)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.is_creator_pro").value(true));
      String event =
          Base64.getEncoder()
              .encodeToString(
                  json.writeValueAsBytes(
                      Map.of(
                          "packageName",
                          "com.castly.fixture",
                          "subscriptionNotification",
                          Map.of("purchaseToken", token, "notificationType", 12))));
      googleService.rtdn("fixture-rtdn-1", event);
      googleService.rtdn("fixture-rtdn-1", event);
      call("GET", "/api/v1/subscriptions/me", TALENT, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.is_creator_pro").value(false));
      state.set("ACTIVE");
      call("POST", "/api/v1/subscriptions/google-play/restore", TALENT, body)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.purchase_state").value("revoked"));
      call("GET", "/api/v1/subscriptions/history", TALENT, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].token_hash").doesNotExist())
          .andExpect(jsonPath("$[0].provider_order_id").doesNotExist());
      mvc.perform(
              post("/api/v1/subscriptions/google-play/rtdn")
                  .header("Authorization", "Bearer forged")
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isUnauthorized());
      org.mockito.Mockito.when(google.get("com.castly.fixture", "fixture-error-token-1234"))
          .thenThrow(
              new org.springframework.web.server.ResponseStatusException(
                  org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Google unavailable"));
      call(
              "POST",
              "/api/v1/subscriptions/google-play/verify",
              TALENT,
              "{\"purchase_token\":\"fixture-error-token-1234\"}")
          .andExpect(status().isServiceUnavailable());
      assertEquals(
          (Object) 0L,
          data.as(
              UUID.fromString(TALENT),
              db ->
                  db.sql("select count(*) from public.subscription_orders where token_hash=:h")
                      .param(
                          "h",
                          com.castly.subscription.GooglePlayService.hash(
                              "fixture-error-token-1234"))
                      .query(Long.class)
                      .single()));
    } finally {
      ownerFixture(
          "delete from public.subscription_audit_logs where action like 'google_%'; delete from"
              + " public.subscription_events; delete from public.subscription_grants where"
              + " source='google_play'; delete from public.subscription_orders; delete from"
              + " private.subscription_purchase_owners; delete from"
              + " private.subscription_rtdn_receipts; delete from public.subscription_entitlements"
              + " where user_id='"
              + TALENT
              + "'; update public.subscription_plans set"
              + " google_product_id=null,google_base_plan_id=null where id='creator_pro'");
    }
  }

  @Test
  void concurrentPromotionApprovalsAndAuditFailureAreAtomic() throws Exception {
    UUID creator = UUID.fromString("00000000-0000-0000-0000-000000000006"),
        reviewer = UUID.fromString("00000000-0000-0000-0000-000000000007");
    ownerFixture("insert into private.subscription_admins values ('" + reviewer + "')");
    UUID claim =
        UUID.fromString(
            promotionService
                .submit(creator, "https://instagram.com/reel/concurrent-approval")
                .get("id")
                .toString());
    try {
      ownerFixture(
          "create function private.fixture_fail_audit() returns trigger language plpgsql as $$begin"
              + " raise exception 'fixture audit failure'; end$$; create trigger fixture_audit"
              + " before insert on public.subscription_audit_logs for each row execute function"
              + " private.fixture_fail_audit()");
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> promotionService.review(reviewer, claim, true, "Verified creator post"));
      assertEquals("pending", promotionService.own(creator).getFirst().get("status"));
      assertEquals(false, membershipService.current(creator).get("is_creator_pro"));
      ownerFixture(
          "drop trigger fixture_audit on public.subscription_audit_logs; drop function"
              + " private.fixture_fail_audit()");
      try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var results =
            executor.invokeAll(
                java.util.List.of(
                    () -> promotionService.review(reviewer, claim, true, "Verified creator post"),
                    () -> promotionService.review(reviewer, claim, true, "Verified creator post")));
        for (var result : results)
          assertEquals("approved", ((Map<?, ?>) result.get()).get("status"));
      }
      assertEquals(
          (Object) 1L,
          data.as(
              creator,
              db ->
                  db.sql(
                          "select count(*) from public.subscription_grants where source_key=:k and"
                              + " expires_at=starts_at+interval '12 months'")
                      .param("k", "promotion:" + claim)
                      .query(Long.class)
                      .single()));
      call("GET", "/api/v1/account/entitlements", creator.toString(), null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").exists())
          .andExpect(jsonPath("$[0].user_id").value(creator.toString()))
          .andExpect(jsonPath("$[0].created_at").exists());
    } finally {
      ownerFixture(
          "drop trigger if exists fixture_audit on public.subscription_audit_logs; drop function if"
              + " exists private.fixture_fail_audit(); delete from public.subscription_audit_logs"
              + " where resource_id='"
              + claim
              + "'; delete from public.subscription_grants where user_id='"
              + creator
              + "'; delete from public.subscription_entitlements where user_id='"
              + creator
              + "'; delete from public.subscription_discovery_eligibility where user_id='"
              + creator
              + "'; delete from public.promotion_claims where id='"
              + claim
              + "'; delete from private.subscription_admins where user_id='"
              + reviewer
              + "'");
    }
  }

  @Test
  void overlappingGrantsExpiryRevocationAndConfiguredPortfolioLimits() throws Exception {
    UUID creator = UUID.fromString("00000000-0000-0000-0000-000000000099");
    ownerFixture(
        "insert into public.talent_profiles(id,display_name,category_id,city,day_rate,published)"
            + " values ('"
            + creator
            + "','Subscription Creator','creators','Mumbai',100,true); insert into"
            + " public.subscription_grants(user_id,entitlement,source,source_key,starts_at,expires_at)"
            + " values ('"
            + creator
            + "','creator_pro','promotion','expiry-fixture',now()-interval '2 years',now()-interval"
            + " '1 year'),('"
            + creator
            + "','creator_pro','google_play','active-fixture',now()-interval '1 day',now()+interval"
            + " '1 month'),('"
            + creator
            + "','future_feature','legacy','future-fixture',now()+interval '1 year',now()+interval"
            + " '2 years'); update public.subscription_plans set portfolio_limit=1 where id='free';"
            + " update public.subscription_plans set portfolio_limit=2 where id='creator_pro'");
    try {
      assertEquals(true, membershipService.current(creator).get("is_creator_pro"));
      assertEquals(1, ((List<?>) membershipService.current(creator).get("entitlements")).size());
      for (int n = 0; n < 2; n++)
        call(
                "POST",
                "/api/v1/talents/me/portfolio",
                creator.toString(),
                "{\"path\":\""
                    + creator
                    + "/image"
                    + n
                    + ".jpg\",\"label\":\"Portfolio\",\"position\":"
                    + n
                    + "}")
            .andExpect(status().isOk());
      call(
              "POST",
              "/api/v1/talents/me/portfolio",
              creator.toString(),
              "{\"path\":\"" + creator + "/third.jpg\",\"label\":\"Portfolio\",\"position\":2}")
          .andExpect(status().isConflict());
      ownerFixture(
          "update public.subscription_grants set revoked_at=now() where"
              + " source_key='active-fixture'");
      assertEquals(false, membershipService.current(creator).get("is_creator_pro"));
      call("GET", "/api/v1/talents/" + creator + "/portfolio", null, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(2));
      call("GET", "/api/v1/account/entitlements", creator.toString(), null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    } finally {
      ownerFixture(
          "delete from public.subscription_grants where user_id='"
              + creator
              + "'; delete from public.portfolio_assets where talent_id='"
              + creator
              + "'; delete from public.talent_profiles where id='"
              + creator
              + "'; update public.subscription_plans set portfolio_limit=null");
    }
  }

  @Test
  void linkedPurchaseRetiresPriorTokenAndConcurrentVerificationGrantsOnce() throws Exception {
    UUID creator = UUID.fromString("00000000-0000-0000-0000-000000000098");
    String oldToken = "replacement-original-token-12345",
        newToken = "replacement-new-token-12345678";
    ownerFixture(
        "update public.subscription_plans set"
            + " google_product_id='creator_pro_fixture',google_base_plan_id='annual' where"
            + " id='creator_pro'");
    org.mockito.Mockito.when(google.configured()).thenReturn(true);
    for (String token : List.of(oldToken, newToken)) {
      var response =
          json.createObjectNode()
              .put("subscriptionState", "SUBSCRIPTION_STATE_ACTIVE")
              .put("acknowledgementState", "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED");
      response
          .putObject("externalAccountIdentifiers")
          .put("obfuscatedExternalAccountId", googleService.accountId(creator));
      var line =
          response
              .putArray("lineItems")
              .addObject()
              .put("productId", "creator_pro_fixture")
              .put("expiryTime", "2030-12-01T00:00:00Z")
              .put(
                  "latestSuccessfulOrderId",
                  token.equals(oldToken) ? "GPA.original" : "GPA.replacement");
      line.putObject("offerDetails").put("basePlanId", "annual");
      if (token.equals(newToken)) response.put("linkedPurchaseToken", oldToken);
      org.mockito.Mockito.when(google.get("com.castly.fixture", token)).thenReturn(response);
    }
    try {
      try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var results =
            executor.invokeAll(
                List.of(
                    () -> googleService.verify(creator, oldToken),
                    () -> googleService.verify(creator, oldToken)));
        for (var result : results)
          assertEquals(true, ((Map<?, ?>) result.get()).get("is_creator_pro"));
      }
      googleService.verify(creator, newToken);
      var denied =
          assertThrows(
              org.springframework.web.server.ResponseStatusException.class,
              () -> googleService.verify(creator, oldToken));
      assertEquals(409, denied.getStatusCode().value());
      assertEquals(
          (Object) 1L,
          data.as(
              creator,
              db ->
                  db.sql(
                          "select count(*) from public.subscription_grants where user_id=:u and"
                              + " revoked_at is null")
                      .param("u", creator)
                      .query(Long.class)
                      .single()));
      String stale =
          Base64.getEncoder()
              .encodeToString(
                  json.writeValueAsBytes(
                      Map.of(
                          "packageName",
                          "com.castly.fixture",
                          "subscriptionNotification",
                          Map.of("purchaseToken", oldToken, "notificationType", 3))));
      googleService.rtdn("replacement-stale-event", stale);
      assertEquals(true, membershipService.current(creator).get("is_creator_pro"));
    } finally {
      ownerFixture(
          "delete from public.subscription_audit_logs where user_id='"
              + creator
              + "'; delete from public.subscription_events where user_id='"
              + creator
              + "'; delete from public.subscription_grants where user_id='"
              + creator
              + "'; delete from public.subscription_orders where user_id='"
              + creator
              + "'; delete from private.subscription_purchase_owners where user_id='"
              + creator
              + "'; delete from private.subscription_replaced_tokens where user_id='"
              + creator
              + "'; delete from private.subscription_rtdn_receipts where"
              + " message_id='replacement-stale-event'; delete from"
              + " public.subscription_entitlements where user_id='"
              + creator
              + "'; delete from public.subscription_discovery_eligibility where user_id='"
              + creator
              + "'; update public.subscription_plans set"
              + " google_product_id=null,google_base_plan_id=null where id='creator_pro'");
    }
  }

  @Test
  void verificationRequiresOwnedPortfolioTrustedReviewerAndAuditsBadgeRevocation()
      throws Exception {
    String creator = "00000000-0000-0000-0000-000000000090",
        reviewer = "00000000-0000-0000-0000-000000000091";
    ownerFixture(
        "insert into public.talent_profiles(id,display_name,category_id,city,day_rate,published)"
            + " values ('"
            + creator
            + "','Verified fixture creator','creators','Mumbai',100,true); insert into"
            + " public.portfolio_assets(id,talent_id,path,label) values"
            + " ('10000000-0000-0000-0000-000000000090','"
            + creator
            + "','"
            + creator
            + "/work.jpg','Owned portfolio')");
    try {
      call("GET", "/api/v1/verification/me", creator, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("NOT_SUBMITTED"));
      call("GET", "/api/v1/verification/contact", creator, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("unavailable"));
      String body =
          json.writeValueAsString(
              Map.of(
                  "statement",
                  "I created this original portfolio and consent to manual review.",
                  "portfolio_asset_ids",
                  List.of("10000000-0000-0000-0000-000000000090"),
                  "consent_version",
                  "profile-review-v1"));
      call("POST", "/api/v1/verification", TALENT, body).andExpect(status().isNotFound());
      var request =
          object(call("POST", "/api/v1/verification", creator, body).andExpect(status().isOk()));
      String id = request.get("id").toString();
      call(
              "POST",
              "/api/v1/admin/verification/" + id + "/decision",
              creator,
              "{\"decision\":\"VERIFIED\",\"reason\":\"Self claim\"}")
          .andExpect(status().isForbidden());
      ownerFixture(
          "insert into trust_private.reviewers values ('" + reviewer + "','verification')");
      call(
              "POST",
              "/api/v1/admin/verification/" + id + "/decision",
              reviewer,
              "{\"decision\":\"UNDER_REVIEW\",\"reason\":\"Review started\"}")
          .andExpect(status().isOk());
      for (int n = 0; n < 2; n++)
        call(
                "POST",
                "/api/v1/admin/verification/" + id + "/decision",
                reviewer,
                "{\"decision\":\"VERIFIED\",\"reason\":\"Profile and owned portfolio manually"
                    + " reviewed\"}")
            .andExpect(status().isOk());
      call("GET", "/api/v1/talents/" + creator, null, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.verified_creator").value(true));
      data.as(
          UUID.fromString(creator),
          db ->
              db.sql("update public.talent_profiles set available=false,day_rate=200 where id=:u")
                  .param("u", UUID.fromString(creator))
                  .update());
      call("GET", "/api/v1/talents/" + creator, null, null)
          .andExpect(jsonPath("$.verified_creator").value(true));
      data.as(
          UUID.fromString(creator),
          db ->
              db.sql(
                      "update public.talent_profiles set display_name='Changed review identity'"
                          + " where id=:u")
                  .param("u", UUID.fromString(creator))
                  .update());
      call("GET", "/api/v1/talents/" + creator, null, null)
          .andExpect(jsonPath("$.verified_creator").value(false));
      call("GET", "/api/v1/verification/me", creator, null)
          .andExpect(jsonPath("$.status").value("REVOKED"));
      call("POST", "/api/v1/verification", creator, body)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.revision").value(2));
      call(
              "POST",
              "/api/v1/admin/verification/" + id + "/decision",
              reviewer,
              "{\"decision\":\"VERIFIED\",\"reason\":\"Changed profile manually reviewed\"}")
          .andExpect(status().isOk());
      call(
              "POST",
              "/api/v1/admin/verification/" + id + "/decision",
              reviewer,
              "{\"decision\":\"REVOKED\",\"reason\":\"Ownership evidence withdrawn\"}")
          .andExpect(status().isOk());
      call("GET", "/api/v1/talents/" + creator, null, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.verified_creator").value(false));
      call("GET", "/api/v1/verification/history", creator, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(4));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              data.as(
                  UUID.fromString(creator),
                  db ->
                      db.sql("insert into trust_private.reviewers values(:u,'verification')")
                          .param("u", UUID.fromString(creator))
                          .update()));
    } finally {
      ownerFixture(
          "delete from public.trust_audit_logs where subject_id='"
              + creator
              + "'; delete from public.verification_decisions where request_id in(select id from"
              + " public.verification_requests where user_id='"
              + creator
              + "'); delete from public.verification_evidence where owner_id='"
              + creator
              + "'; delete from public.verification_revisions where owner_id='"
              + creator
              + "'; delete from public.verification_requests where user_id='"
              + creator
              + "'; delete from public.creator_verification_badges where user_id='"
              + creator
              + "'; delete from public.portfolio_assets where talent_id='"
              + creator
              + "'; delete from public.talent_profiles where id='"
              + creator
              + "'; delete from trust_private.reviewers where user_id='"
              + reviewer
              + "'");
    }
  }

  @Test
  void entitlementReadUsesStatementTimeAfterWaitingForLaterGrant() throws Exception {
    UUID creator = UUID.fromString("00000000-0000-0000-0000-000000000089");
    var started = new java.util.concurrent.CountDownLatch(1);
    var committed = new java.util.concurrent.CountDownLatch(1);
    var repository = new com.castly.subscription.SubscriptionRepository();
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var waiting =
          executor.submit(
              () ->
                  data.as(
                      creator,
                      db -> {
                        db.sql("select now()").query().singleRow();
                        started.countDown();
                        try {
                          if (!committed.await(20, java.util.concurrent.TimeUnit.SECONDS))
                            throw new AssertionError("Writer did not commit");
                        } catch (InterruptedException e) {
                          throw new RuntimeException(e);
                        }
                        repository.lock(db, creator);
                        assertEquals(
                            0L,
                            db.sql(
                                    "select count(*) from public.subscription_grants where"
                                        + " user_id=:u and starts_at<=now()")
                                .param("u", creator)
                                .query(Long.class)
                                .single());
                        return repository.effective(db, creator);
                      }));
      assertTrue(started.await(20, java.util.concurrent.TimeUnit.SECONDS));
      ownerFixture(
          "insert into public.subscription_grants(user_id,entitlement,source,source_key) values('"
              + creator
              + "','creator_pro','legacy','temporal-fixture')");
      committed.countDown();
      assertEquals(1, waiting.get(20, java.util.concurrent.TimeUnit.SECONDS).size());
    } finally {
      committed.countDown();
      ownerFixture("delete from public.subscription_grants where source_key='temporal-fixture'");
    }
  }

  @Test
  void explicitMutualCompletionControlsReviewsAndPreservesConversation() throws Exception {
    String booking = newBooking(), talent = talentFor(booking);
    String review = "{\"rating\":5,\"body\":\"Completed project review\"}";
    call("POST", "/api/v1/bookings/" + booking + "/reviews", CLIENT, review)
        .andExpect(status().isConflict());
    call("POST", "/api/v1/bookings/" + booking + "/accept", talent, null)
        .andExpect(status().isOk());
    call("POST", "/api/v1/bookings/" + booking + "/completion", OUTSIDER, null)
        .andExpect(status().isNotFound());
    call("POST", "/api/v1/bookings/" + booking + "/completion", CLIENT, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.booking.status").value("accepted"));
    call("POST", "/api/v1/bookings/" + booking + "/completion", CLIENT, null)
        .andExpect(jsonPath("$.confirmation_count").value(1));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            data.as(
                UUID.fromString(CLIENT),
                db ->
                    db.sql("update public.bookings set status='completed' where id=:b")
                        .param("b", UUID.fromString(booking))
                        .update()));
    call("POST", "/api/v1/bookings/" + booking + "/reviews", CLIENT, review)
        .andExpect(status().isConflict());
    call("POST", "/api/v1/bookings/" + booking + "/completion", talent, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.booking.status").value("completed"));
    String conversation =
        object(
                call(
                        "POST",
                        "/api/v1/conversations",
                        CLIENT,
                        "{\"booking_id\":\"" + booking + "\"}")
                    .andExpect(status().isOk()))
            .get("id")
            .toString();
    call(
            "POST",
            "/api/v1/conversations/" + conversation + "/messages",
            CLIENT,
            "{\"body\":\"Completed booking conversation remains available\"}")
        .andExpect(status().isCreated());
    var created =
        object(
            call("POST", "/api/v1/bookings/" + booking + "/reviews", CLIENT, review)
                .andExpect(status().isOk()));
    String id = created.get("id").toString();
    call("POST", "/api/v1/bookings/" + booking + "/reviews", CLIENT, review)
        .andExpect(jsonPath("$.id").value(id));
    call(
            "POST",
            "/api/v1/bookings/" + booking + "/reviews",
            CLIENT,
            "{\"rating\":4,\"body\":\"Duplicate changed content\"}")
        .andExpect(status().isConflict());
    call("PATCH", "/api/v1/reviews/" + id, talent, review).andExpect(status().isNotFound());
    call(
            "PATCH",
            "/api/v1/reviews/" + id,
            CLIENT,
            "{\"rating\":4,\"body\":\"Edited within allowed window\"}")
        .andExpect(status().isOk());
    call("GET", "/api/v1/talents/" + talent + "/reviews", OUTSIDER, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.review_count").value(1))
        .andExpect(jsonPath("$.reviews[0].booking_id").doesNotExist())
        .andExpect(jsonPath("$.reviews[0].reviewer_id").doesNotExist());
    call(
            "POST",
            "/api/v1/admin/reviews/" + id + "/moderation",
            CLIENT,
            "{\"hidden\":true,\"reason\":\"Self moderation\"}")
        .andExpect(status().isForbidden());
    ownerFixture("insert into trust_private.reviewers values('" + OUTSIDER + "','moderation')");
    try {
      call(
              "POST",
              "/api/v1/admin/reviews/" + id + "/moderation",
              OUTSIDER,
              "{\"hidden\":true,\"reason\":\"Confirmed policy violation\"}")
          .andExpect(status().isOk());
      call("GET", "/api/v1/talents/" + talent + "/reviews", CLIENT, null)
          .andExpect(jsonPath("$.summary.review_count").value(0));
      call(
              "POST",
              "/api/v1/admin/reviews/" + id + "/moderation",
              OUTSIDER,
              "{\"hidden\":false,\"reason\":\"Appeal accepted by reviewer\"}")
          .andExpect(status().isOk());
      call("GET", "/api/v1/talents/" + talent + "/reviews", CLIENT, null)
          .andExpect(jsonPath("$.summary.average_rating").value(4.0));
    } finally {
      ownerFixture("delete from trust_private.reviewers where user_id='" + OUTSIDER + "'");
    }
  }
}
