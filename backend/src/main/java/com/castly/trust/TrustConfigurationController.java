package com.castly.trust;

import static com.castly.api.MarketplaceController.actor;

import com.castly.api.ActorDatabase;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/safety")
public class TrustConfigurationController {
  private final ActorDatabase data;
  private final Map<String, Boolean> flags;

  public TrustConfigurationController(
      ActorDatabase data,
      @Value("${castly.trust.verification-enabled:false}") boolean verification,
      @Value("${castly.trust.reviews-enabled:false}") boolean reviews,
      @Value("${castly.trust.reporting-enabled:false}") boolean reporting,
      @Value("${castly.trust.signals-enabled:false}") boolean signals,
      @Value("${castly.trust.disputes-enabled:false}") boolean disputes) {
    this.data = data;
    this.flags =
        Map.of(
            "verification",
            verification,
            "reviews",
            reviews,
            "reporting",
            reporting,
            "trust_signals",
            signals,
            "disputes",
            disputes);
  }

  @GetMapping("/configuration")
  public Map<String, Object> configuration(@AuthenticationPrincipal Jwt jwt) {
    return Map.of(
        "features",
        flags,
        "reviewer_scopes",
        flags.containsValue(true)
            ? data.as(
                actor(jwt),
                db ->
                    db.sql(
                            "select scope from trust_private.reviewers where user_id=:u order by"
                                + " scope")
                        .param("u", actor(jwt))
                        .query(String.class)
                        .list())
            : List.of());
  }
}
