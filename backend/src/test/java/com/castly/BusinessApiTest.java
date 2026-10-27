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
}
