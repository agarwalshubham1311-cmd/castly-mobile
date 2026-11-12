package com.castly.engagement;

public final class NotificationTemplates {
  private NotificationTemplates() {}

  public static String title(String kind) {
    return switch (kind) {
      case "booking_requested" -> "Booking request";
      case "booking_accepted" -> "Booking accepted";
      case "booking_declined" -> "Booking declined";
      case "booking_cancelled" -> "Booking cancelled";
      case "message_received" -> "New message";
      case "booking_reminder" -> "Booking reminder";
      case "saved_digest" -> "Saved search update";
      case "reengagement" -> "Your Castly preferences";
      default -> "Account activity";
    };
  }

  public static String html(String kind, String link, String unsubscribe) {
    return "<!doctype html><html><body"
               + " style=\"background:#f7f3ff;color:#19102d;font-family:Arial,sans-serif;padding:24px\"><h1"
               + " style=\"color:#6431c5\">Castly</h1><h2>"
        + title(kind)
        + "</h2><p>You have an update. Sign in to Castly to view authorized details.</p><p><a"
        + " href=\""
        + escape(link)
        + "\">Open Castly securely</a></p>"
        + (unsubscribe == null
            ? ""
            : "<p><a href=\""
                + escape(unsubscribe)
                + "\">Manage or unsubscribe from optional email</a></p>")
        + "<p>This email contains no private message or booking details.</p></body></html>";
  }

  static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  public static boolean optional(String kind) {
    return java.util.Set.of("booking_reminder", "saved_digest", "reengagement").contains(kind);
  }
}
