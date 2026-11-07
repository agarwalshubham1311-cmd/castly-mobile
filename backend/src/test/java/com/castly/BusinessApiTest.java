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
        data.as(client, db -> db.sql("select auth.uid()::text").query(String.class).single()));
    assertEquals(
        "",
        data.as(
            null,
            db -> db.sql("select coalesce(auth.uid()::text,'')").query(String.class).single()));
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
                                + " values(auth.uid(),'forged')")
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
                              db.sql("select auth.uid()::text").query(String.class).single());
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
                "", db.sql("select coalesce(auth.uid()::text,'')").query(String.class).single());
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
                                        + " values(:conv,auth.uid(),'Before cancellation')")
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
                                  + " values(:conv,auth.uid(),'Blocked direct SQL')")
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
              + " with check((client_id=(select auth.uid()) or talent_id=(select auth.uid())) and"
              + " exists(select 1 from public.bookings b where b.client_id=conversations.client_id"
              + " and b.talent_id=conversations.talent_id and b.status='accepted'))");
    }
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
                                + " values(auth.uid(),'booking_accepted','forged',:id)")
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
  void authSchemaUsageAloneRepairsAcceptanceWithoutWideningBusinessPrivileges() throws Exception {
    String id = newBooking(), talent = talentFor(id);
    String migration =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "../supabase/migrations/20261107130000_restore_api_auth_schema_usage.sql"));
    localOwner("revoke usage on schema auth from castly_api");
    try {
      // Cached function plans can retain resolved OIDs. Reproduce missing name-resolution
      // privileges on fresh physical connections, not accidentally precompiled trigger plans.
      ((com.zaxxer.hikari.HikariDataSource) pool).getHikariPoolMXBean().softEvictConnections();
      var booking =
          data.as(
              UUID.fromString(talent),
              db -> {
                assertFalse(
                    db.sql("select has_schema_privilege(current_user,'auth','USAGE')")
                        .query(Boolean.class)
                        .single());
                assertTrue(
                    db.sql(
                            "select has_table_privilege(current_user,'public.bookings','SELECT')"
                                + " and has_table_privilege(current_user,'public.users','SELECT')")
                        .query(Boolean.class)
                        .single());
                // OID-based ACL inspection does not itself require resolving the name auth.uid().
                assertTrue(
                    db.sql(
                            "select has_function_privilege(current_user,p.oid,'EXECUTE')"
                                + " from pg_proc p join pg_namespace n on n.oid=p.pronamespace"
                                + " where n.nspname='auth' and p.proname='uid' and p.pronargs=0")
                        .query(Boolean.class)
                        .single());
                return ActorDatabase.one(
                    db.sql("select * from public.bookings where id=:id for update")
                        .param("id", UUID.fromString(id)));
              });
      var uidDenied =
          assertThrows(
              org.springframework.dao.DataAccessException.class,
              () ->
                  data.as(
                      UUID.fromString(talent),
                      db -> db.sql("select auth.uid()").query().singleRow()));
      assertEquals("42501", com.castly.api.SqlFailureKind.sqlState(uidDenied));
      assertEquals("postgres_privilege_denied", com.castly.api.SqlFailureKind.of(uidDenied));
      assertTrue(
          uidDenied
              .getMostSpecificCause()
              .getMessage()
              .contains("permission denied for schema auth"));
      var evidence =
          data.as(
              UUID.fromString(talent),
              db ->
                  new com.castly.api.BookingUpdateDiagnostics(true)
                      .capture(db, UUID.fromString(talent), booking));
      assertEquals("postgres_privilege_denied", evidence.probe());
      assertNull(evidence.identityMatches());
      var updateDenied =
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
      assertEquals("42501", com.castly.api.SqlFailureKind.sqlState(updateDenied));
      assertTrue(
          updateDenied
              .getMostSpecificCause()
              .getMessage()
              .contains("permission denied for schema auth"));
      assertTrue(
          updateDenied
              .getMostSpecificCause()
              .getMessage()
              .contains("api_booking_transition_guard"));
      var logger =
          (ch.qos.logback.classic.Logger)
              org.slf4j.LoggerFactory.getLogger(com.castly.api.ApiErrors.class);
      var logs =
          new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
      logs.start();
      logger.addAppender(logs);
      try {
        call("POST", "/api/v1/bookings/" + id + "/accept", talent, null)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.detail").value("Not permitted"))
            .andExpect(jsonPath("$.operation").value("booking.accepted.update"));
        String line = logs.list.getLast().getFormattedMessage();
        assertTrue(line.contains("reason=postgres_privilege_denied"));
        assertTrue(line.contains("probe=postgres_privilege_denied"));
        assertFalse(line.contains(id));
        assertFalse(line.contains(talent));
      } finally {
        logger.detachAppender(logs);
        logs.stop();
      }
      call("GET", "/api/v1/bookings/" + id, CLIENT, null)
          .andExpect(jsonPath("$.status").value("requested"));
      assertEquals(
          (Object) 0,
          data.as(
              UUID.fromString(CLIENT),
              db ->
                  db.sql("select count(*)::int from public.conversations where talent_id=:talent")
                      .param("talent", UUID.fromString(talent))
                      .query(Integer.class)
                      .single()));
      assertEquals(
          (Object) 0,
          data.as(
              UUID.fromString(CLIENT),
              db ->
                  db.sql(
                          "select count(*)::int from public.notifications where booking_id=:id"
                              + " and kind='booking_accepted'")
                      .param("id", UUID.fromString(id))
                      .query(Integer.class)
                      .single()));

      // Apply the exact reviewed artifact twice, only inside the disposable local fixture.
      localOwner(migration);
      localOwner(migration);
      var repaired =
          data.as(
              UUID.fromString(talent),
              db -> {
                assertTrue(
                    db.sql("select has_schema_privilege(current_user,'auth','USAGE')")
                        .query(Boolean.class)
                        .single());
                assertEquals(
                    talent, db.sql("select auth.uid()::text").query(String.class).single());
                return new com.castly.api.BookingUpdateDiagnostics(true)
                    .capture(db, UUID.fromString(talent), booking);
              });
      assertEquals("ok", repaired.probe());
      assertEquals(true, repaired.identityMatches());
      assertEquals(true, repaired.accountVisible());
      assertEquals(true, repaired.roleMatches());
      call("POST", "/api/v1/bookings/" + id + "/accept", CLIENT, null)
          .andExpect(status().isForbidden());
      call(
              "POST",
              "/api/v1/bookings/" + id + "/accept",
              "00000000-0000-0000-0000-000000000005",
              null)
          .andExpect(status().isNotFound());
      call("POST", "/api/v1/bookings/" + id + "/accept", talent, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("accepted"));
      call("POST", "/api/v1/bookings/" + id + "/accept", talent, null).andExpect(status().isOk());
      var conversation =
          object(
              call("POST", "/api/v1/conversations", CLIENT, "{\"booking_id\":\"" + id + "\"}")
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.client_id").value(CLIENT))
                  .andExpect(jsonPath("$.talent_id").value(talent)));
      assertEquals(
          (Object) 1,
          data.as(
              UUID.fromString(talent),
              db ->
                  db.sql("select count(*)::int from public.conversations where talent_id=:talent")
                      .param("talent", UUID.fromString(talent))
                      .query(Integer.class)
                      .single()));
      call("GET", "/api/v1/conversations/" + conversation.get("id"), OUTSIDER, null)
          .andExpect(status().isNotFound());
      call("GET", "/api/v1/bookings/" + id, OUTSIDER, null).andExpect(status().isNotFound());
      data.as(
          UUID.fromString(talent),
          db -> {
            assertEquals(
                0,
                db.sql("select count(*)::int from public.users where id=:client")
                    .param("client", UUID.fromString(CLIENT))
                    .query(Integer.class)
                    .single());
            assertFalse(
                db.sql(
                        "select has_table_privilege(current_user,'public.bookings','UPDATE') or"
                            + " has_column_privilege(current_user,'public.bookings','updated_at','UPDATE')"
                            + " or has_schema_privilege(current_user,'auth','CREATE')")
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
                        "select prosecdef from pg_proc where oid="
                            + "'public.api_booking_transition_guard()'::regprocedure")
                    .query(Boolean.class)
                    .single());
            assertTrue(
                db.sql(
                        "select bool_and(relrowsecurity) from pg_class where oid in"
                            + " ('public.bookings'::regclass,'public.users'::regclass,'public.conversations'::regclass)")
                    .query(Boolean.class)
                    .single());
            return null;
          });
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              data.as(
                  UUID.fromString(talent),
                  db -> db.sql("select id from auth.users limit 1").query().listOfRows()));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              data.as(
                  UUID.fromString(talent),
                  db ->
                      db.sql("update public.bookings set agreed_rate=1 where id=:id")
                          .param("id", UUID.fromString(id))
                          .update()));
    } finally {
      localOwner("grant usage on schema auth to castly_api");
    }
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
}
