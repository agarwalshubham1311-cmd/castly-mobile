package com.castly;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/castly_unmigrated_test",
      "spring.datasource.username=castly_api",
      "spring.datasource.password=castly-api-local-test",
      "castly.jwt.issuer=http://127.0.0.1:59999/auth/v1",
      "castly.jwt.mode=jwks",
      "castly.notifications.enabled=false",
      "castly.subscriptions.enabled=false",
      "castly.trust.verification-enabled=false",
      "castly.trust.reviews-enabled=false",
      "castly.trust.reporting-enabled=false",
      "castly.trust.signals-enabled=false",
      "castly.trust.disputes-enabled=false"
    })
@AutoConfigureMockMvc
class UnmigratedTrustCompatibilityTest {
  @org.springframework.test.context.DynamicPropertySource
  static void prepare(org.springframework.test.context.DynamicPropertyRegistry registry) {
    try {
      var command = new ProcessBuilder("node", "scripts/prepare-test-db.cjs").inheritIO();
      command.environment().put("CASTLY_TEST_SKIP_PHASE4", "true");
      command.environment().put("CASTLY_TEST_DATABASE", "castly_unmigrated_test");
      if (command.start().waitFor() != 0) throw new IllegalStateException("Fixture failed");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired com.castly.trust.TrustSchema schema;

  private org.springframework.test.web.servlet.ResultActions call(
      String method, String path, String actor, String body) throws Exception {
    var request =
        request(org.springframework.http.HttpMethod.valueOf(method), "/api/v1" + path)
            .with(
                jwt()
                    .jwt(
                        j ->
                            j.subject(actor)
                                .claim("role", "authenticated")
                                .audience(List.of("authenticated"))));
    if (body != null) request.contentType("application/json").content(body);
    return mvc.perform(request);
  }

  private String id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asText();
  }

  @Test
  void disabledTrustDoesNotRequireNewSchemaForExistingBookingAndMessaging() throws Exception {
    assertFalse(schema.blockingReady());
    String client = BusinessApiTest.CLIENT, talent = BusinessApiTest.TALENT;
    call(
            "POST",
            "/talents/me",
            talent,
            "{\"display_name\":\"Unmigrated"
                + " creator\",\"category_id\":\"actors\",\"city\":\"Mumbai\",\"day_rate\":1000,\"bio\":\"Existing"
                + " profile\",\"skills\":[],\"available\":true,\"published\":true,\"portrait_path\":null}")
        .andExpect(status().isOk());
    String booking =
        id(
            call(
                    "POST",
                    "/bookings",
                    client,
                    "{\"talent_id\":\""
                        + talent
                        + "\",\"project_type\":\"Existing flow\",\"proposed_date\":\""
                        + java.time.LocalDate.now().plusDays(2)
                        + "\",\"location\":\"Mumbai\",\"requirements\":\"Preserve existing booking"
                        + " behavior\",\"indicative_budget\":1000}")
                .andExpect(status().isCreated()));
    call("POST", "/bookings/" + booking + "/accept", talent, null).andExpect(status().isOk());
    String conversation =
        id(
            call("POST", "/conversations", client, "{\"booking_id\":\"" + booking + "\"}")
                .andExpect(status().isOk()));
    call(
            "POST",
            "/conversations/" + conversation + "/messages",
            client,
            "{\"body\":\"Existing message without Phase4 schema\"}")
        .andExpect(status().isCreated());
    call("GET", "/conversations/" + conversation, talent, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.can_send").value(true));
    call("GET", "/conversations/" + conversation + "/messages", talent, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    call("POST", "/bookings/" + booking + "/cancel", client, null).andExpect(status().isOk());
    call("GET", "/safety/configuration", client, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.features.reporting").value(false));
    call("GET", "/blocks", client, null).andExpect(status().isServiceUnavailable());
  }
}
