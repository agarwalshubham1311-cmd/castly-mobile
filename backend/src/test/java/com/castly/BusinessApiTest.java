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
}
