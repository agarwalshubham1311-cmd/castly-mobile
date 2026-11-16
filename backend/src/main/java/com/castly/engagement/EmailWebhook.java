package com.castly.engagement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class EmailWebhook {
  private EmailWebhook() {}

  public static boolean verify(
      String key, String id, String timestamp, String signatures, byte[] body, Instant now) {
    try {
      if (!key.startsWith("whsec_")
          || id == null
          || !id.matches("[A-Za-z0-9_-]{1,200}")
          || timestamp == null
          || signatures == null
          || signatures.length() > 1000
          || (Long.parseLong(timestamp) < now.getEpochSecond() - 300
              || Long.parseLong(timestamp) > now.getEpochSecond() + 300)
          || body.length > 65536) return false;
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(Base64.getDecoder().decode(key.substring(6)), "HmacSHA256"));
      var prefix = (id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
      mac.update(prefix);
      byte[] wanted = mac.doFinal(body);
      for (String s : signatures.split(" "))
        if (s.startsWith("v1,")
            && MessageDigest.isEqual(wanted, Base64.getDecoder().decode(s.substring(3))))
          return true;
      return false;
    } catch (Exception e) {
      return false;
    }
  }
}
