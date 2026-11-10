package com.castly.trust;

import com.castly.api.ActorDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Read-only startup capability probe. Disabled deployments remain compatible before migrations. */
@Component
public class TrustSchema {
  private final boolean blockingReady;

  public TrustSchema(
      ActorDatabase data,
      @Value("${castly.trust.verification-enabled:false}") boolean verification,
      @Value("${castly.trust.reviews-enabled:false}") boolean reviews,
      @Value("${castly.trust.reporting-enabled:false}") boolean reporting,
      @Value("${castly.trust.signals-enabled:false}") boolean signals,
      @Value("${castly.trust.disputes-enabled:false}") boolean disputes) {
    blockingReady =
        data.as(
            null,
            db ->
                db.sql("select to_regprocedure('trust_private.blocked(uuid,uuid)') is not null")
                    .query(Boolean.class)
                    .single());
    if ((verification || reviews || reporting || signals || disputes) && !blockingReady)
      throw new IllegalStateException(
          "Phase 4 migrations must be reviewed and applied before enabling trust features");
  }

  public boolean blockingReady() {
    return blockingReady;
  }
}
