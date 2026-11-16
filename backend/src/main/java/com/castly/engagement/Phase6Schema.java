package com.castly.engagement;

import com.castly.api.ActorDatabase;
import org.springframework.stereotype.Component;

@Component
public class Phase6Schema {
  public Phase6Schema(ActorDatabase data, Phase6Settings flags) {
    if (!flags.any()) return;
    boolean ready =
        data.as(
            null,
            db ->
                db.sql(
                        "select"
                            + " coalesce(has_table_privilege(current_user,to_regclass('engagement_private.outbox'),'SELECT'),false)"
                            + " and to_regclass('public.engagement_preferences') is not null and"
                            + " to_regclass('public.saved_searches') is not null and"
                            + " to_regprocedure('engagement_private.record_event(text)') is not"
                            + " null")
                    .query(Boolean.class)
                    .single());
    if (!ready)
      throw new IllegalStateException("Reviewed Phase 6 migration required before activation");
  }
}
