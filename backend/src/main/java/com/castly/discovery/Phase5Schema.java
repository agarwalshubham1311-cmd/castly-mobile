package com.castly.discovery;

import com.castly.api.ActorDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Enabled modules fail startup before serving requests if their forward schema is absent. */
@Component
public class Phase5Schema {
  public Phase5Schema(ActorDatabase data,
      @Value("${castly.phase5.advanced-search-enabled:false}") boolean advanced,
      @Value("${castly.phase5.ai-recommendations-enabled:false}") boolean recommendations,
      @Value("${castly.phase5.smart-matching-enabled:false}") boolean matching,
      @Value("${castly.phase5.ai-assistance-enabled:false}") boolean assistance) {
    if (!(advanced || recommendations || matching || assistance)) return;
    boolean ready = data.as(null, db -> db.sql(
        "select coalesce(has_table_privilege(current_user,to_regclass('public.talent_discovery_preferences'),'SELECT'),false)"
            + " and coalesce(has_table_privilege(current_user,to_regclass('public.talent_availability_days'),'SELECT'),false)"
            + " and coalesce(has_table_privilege(current_user,to_regclass('public.talent_booking_busy_days'),'SELECT'),false)"
            + " and coalesce(has_table_privilege(current_user,to_regclass('public.phase5_request_limits'),'SELECT'),false)")
        .query(Boolean.class).single());
    if (!ready) throw new IllegalStateException("Enabled Phase 5 feature requires the reviewed Phase 5 schema and grants");
  }
}
