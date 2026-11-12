package com.castly.engagement;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class Phase6Signals {
  private final Phase6Settings flags;

  public Phase6Signals(Phase6Settings flags) {
    this.flags = flags;
  }

  public void record(JdbcClient db, String stage) {
    if (flags.analytics || flags.engagement)
      db.sql("select engagement_private.record_event(:stage)")
          .param("stage", stage)
          .query()
          .singleRow();
  }
}
