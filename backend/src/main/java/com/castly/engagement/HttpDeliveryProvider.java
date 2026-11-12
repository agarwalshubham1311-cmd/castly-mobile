package com.castly.engagement;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** No requests when providers are disabled. No response bodies or destinations are logged. */
public class HttpDeliveryProvider implements DeliveryProvider {
  private final Phase6Settings flags;
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public HttpDeliveryProvider(Phase6Settings flags, ObjectMapper json) {
    this.flags = flags;
    this.json = json;
  }

  private JsonNode request(String endpoint, Object body, String key, UUID id) throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(4))
            .header("Content-Type", "application/json");
    if (key != null)
      b.header("Authorization", "Bearer " + key).header("Idempotency-Key", id.toString());
    var r =
        http.send(
            b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (var input = r.body()) {
      byte[] bytes = input.readNBytes(65537);
      if (r.statusCode() == 429) return json.createObjectNode().put("retry", true);
      if (r.statusCode() >= 500) return json.createObjectNode().put("uncertain", true);
      if (r.statusCode() < 200 || r.statusCode() >= 300 || bytes.length > 65536)
        return json.createObjectNode().put("permanent", true);
      return json.readTree(bytes);
    }
  }

  public Result send(Envelope e) {
    boolean push = e.channel().equals("push");
    if (!flags.worker
        || (push
            ? (!flags.push || !flags.pushProvider.equals("expo"))
            : (!flags.email || !flags.emailProvider.equals("resend"))))
      return Result.of(Status.DISABLED);
    try {
      JsonNode r;
      if (push) {
        r =
            request(
                "https://exp.host/--/api/v2/push/send",
                Map.of(
                    "to",
                    e.destination(),
                    "title",
                    "Castly update",
                    "body",
                    "Open Castly to view your update.",
                    "sound",
                    "default",
                    "channelId",
                    "castly-updates",
                    "data",
                    Map.of("delivery_id", e.id().toString())),
                null,
                e.id());
        if (r.path("retry").asBoolean()) return Result.of(Status.RETRY);
        if (r.path("uncertain").asBoolean()) return Result.of(Status.UNCERTAIN);
        var d = r.path("data");
        if (d.path("status").asText().equals("ok")
            && d.path("id").asText().matches("[A-Za-z0-9-]{1,200}"))
          return new Result(Status.ACCEPTED, d.path("id").asText());
        if (d.path("details").path("error").asText().equals("DeviceNotRegistered"))
          return Result.of(Status.INVALID);
        return Result.of(Status.PERMANENT);
      }
      var headers = new LinkedHashMap<String, String>();
      if (e.unsubscribe() != null) {
        headers.put("List-Unsubscribe", "<" + e.unsubscribe() + ">");
        headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
      }
      r =
          request(
              "https://api.resend.com/emails",
              Map.of(
                  "from",
                  flags.emailFrom,
                  "to",
                  List.of(e.destination()),
                  "subject",
                  "Castly · " + NotificationTemplates.title(e.kind()),
                  "html",
                  NotificationTemplates.html(e.kind(), e.link(), e.unsubscribe()),
                  "headers",
                  headers),
              flags.emailKey,
              e.id());
      if (r.path("retry").asBoolean()) return Result.of(Status.RETRY);
      if (r.path("uncertain").asBoolean()) return Result.of(Status.UNCERTAIN);
      String receipt = r.path("id").asText();
      return receipt.matches("[A-Za-z0-9-]{1,200}")
          ? new Result(Status.ACCEPTED, receipt)
          : Result.of(Status.PERMANENT);
    } catch (Exception ex) {
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
      return Result.of(Status.UNCERTAIN);
    }
  }

  public Result receipt(String receipt) {
    if (!flags.worker || !flags.push || !flags.pushProvider.equals("expo"))
      return Result.of(Status.DISABLED);
    try {
      var r =
          request(
              "https://exp.host/--/api/v2/push/getReceipts",
              Map.of("ids", List.of(receipt)),
              null,
              UUID.randomUUID());
      var d = r.path("data").path(receipt);
      if (d.path("status").asText().equals("ok")) return Result.of(Status.DELIVERED);
      if (d.path("details").path("error").asText().equals("DeviceNotRegistered"))
        return Result.of(Status.INVALID);
      return d.path("status").asText().equals("error")
          ? Result.of(Status.PERMANENT)
          : Result.of(Status.PENDING);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return Result.of(Status.PENDING);
    }
  }
}
