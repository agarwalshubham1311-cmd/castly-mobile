package com.castly.admin;

import com.castly.api.ActorDatabase;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class AdminSchema implements InitializingBean {
  private final ActorDatabase data;
  private final AdminSettings settings;

  public AdminSchema(ActorDatabase data, AdminSettings settings) {
    this.data = data;
    this.settings = settings;
  }

  @Override
  public void afterPropertiesSet() {
    if (settings.enabled())
      data.as(
          null,
          db -> {
            if (db.sql(
                        "select count(*) from admin_private.role_permissions where"
                            + " role='SUPER_ADMIN'")
                    .query(Integer.class)
                    .single()
                != 12) throw new IllegalStateException("Reviewed M7.1 schema is required");
            return null;
          });
  }
}
