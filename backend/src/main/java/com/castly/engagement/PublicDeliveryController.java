package com.castly.engagement;

import jakarta.servlet.http.*;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/engagement")
public class PublicDeliveryController {
  private final ObjectProvider<EngagementWorker> workers;

  public PublicDeliveryController(ObjectProvider<EngagementWorker> workers) {
    this.workers = workers;
  }

  @GetMapping(value = "/unsubscribe/{token}", produces = "text/html")
  public String confirm(@PathVariable String token) {
    return "<!doctype html><html><body><h1>Castly optional email</h1><p>Stop optional reminders,"
        + " digests and re-engagement. Booking/account preferences and required Auth"
        + " security email are separate.</p><form method=\"post\"><button"
        + " type=\"submit\">Unsubscribe from optional email</button></form></body></html>";
  }

  @PostMapping("/unsubscribe/{token}")
  public ResponseEntity<Void> unsubscribe(@PathVariable String token) {
    var worker = workers.getIfAvailable();
    if (worker == null)
      return ResponseEntity.status(503).header("Cache-Control", "no-store").build();
    worker.unsubscribe(token, Instant.now());
    return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
  }

  @PostMapping("/webhooks/email")
  public ResponseEntity<Void> webhook(HttpServletRequest request) throws java.io.IOException {
    var worker = workers.getIfAvailable();
    byte[] body = request.getInputStream().readNBytes(65537);
    if (body.length > 65536) return ResponseEntity.status(413).build();
    if (worker == null
        || !worker.webhook(
            request.getHeader("svix-id"),
            request.getHeader("svix-timestamp"),
            request.getHeader("svix-signature"),
            body,
            Instant.now())) return ResponseEntity.status(401).build();
    return ResponseEntity.noContent().build();
  }
}
