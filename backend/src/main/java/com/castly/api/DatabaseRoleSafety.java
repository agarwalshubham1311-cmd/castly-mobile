package com.castly.api;

import java.util.Map;

/** Shared fail-closed role checks for server startup and read-only deployment diagnostics. */
public final class DatabaseRoleSafety {
  private DatabaseRoleSafety() {}

  public static final String QUERY =
      "select current_user as"
          + " name,rolsuper,rolbypassrls,rolcreatedb,rolcreaterole,rolreplication,has_schema_privilege(current_user,'public','CREATE')"
          + " as schema_create,has_database_privilege(current_user,current_database(),'CREATE') as"
          + " db_create,exists(select 1 from pg_auth_members where member=r.oid) as"
          + " memberships,exists(select 1 from pg_class where relowner=r.oid and relkind in"
          + " ('r','p')) as owns_tables from pg_roles r where rolname=current_user";

  public static void requireRestricted(Map<String, Object> role) {
    if (!"castly_api".equals(role.get("name"))
        || java.util.stream.Stream.of(
                "rolsuper",
                "rolbypassrls",
                "rolcreatedb",
                "rolcreaterole",
                "rolreplication",
                "schema_create",
                "db_create",
                "memberships",
                "owns_tables")
            .anyMatch(key -> !Boolean.FALSE.equals(role.get(key))))
      throw new IllegalStateException(
          "Database login must be the dedicated least-privilege castly_api role");
  }
}
