# P0 acceptance: auth schema USAGE repair for review

2026-11-07. Branch: `feat/milestone5-booking-messaging-notifications`. **No hosted SQL has been applied.** This change adds a forward grant-only migration and local regression coverage. It changes no application code, credentials, TLS/network configuration, existing migration, RLS policy, trigger definition or function security mode.

## Root cause and matching reproduction

The reported hosted metadata establishes that `castly_api` lacks USAGE on schema `auth`, while booking/user SELECT grants exist. The error is42501 `postgres_privilege_denied`, including in the diagnostic probe. Missing schema USAGE prevents resolving a schema-qualified `auth.uid()` call even when EXECUTE on the function itself is allowed.

The disposable PostgreSQL reproduction confirms the exact failure at:

```sql
update public.bookings set status='accepted' where id=:id returning *;
```

PostgreSQL reports **`permission denied for schema auth`**, SQLSTATE42501. The PL/pgSQL error context identifies `api_booking_transition_guard()`, whose declaration initializes `me uuid := auth.uid()`. Because this function is SECURITY INVOKER, that expression resolves as `castly_api`. It fails before reaching the trigger's explicit participant, protected-field, account-role and transition rejections. The probe also resolves `auth.uid()` by name and fails for the same missing prerequisite; null identity fields here mean **not established**, not proof that JWT binding is wrong.

The actual API reproduction emits the same supplied signature, without sensitive values:

```text
operation=booking.accepted.update sqlstate=42501
exception_class=BadSqlGrammarException cause_class=PSQLException
reason=postgres_privilege_denied
booking_diagnostics=Evidence[probe=postgres_privilege_denied,
 identityPresent=null, identityMatches=null, accountVisible=null, roleMatches=null,
 callerIsTalent=true, priorRequested=true]
```

This also explains why the booking SELECT/lock can succeed. In the tested schema, existing RLS expressions already reference function OIDs, while the trigger's PL/pgSQL expression and probe must resolve a textual qualified function name. Missing USAGE does not imply every previously parsed/stored expression will fail. The test recycles only **local fixture** physical connections before reproduction to avoid cached function plans concealing missing name-resolution privileges.

The missing privilege is already granted by both the repository's original Spring migration and issue-1 reconciliation preparation. The repair restores that expected prerequisite; it does not introduce a new authorization model. How the hosted privilege became absent is not established. The observed hosted metadata and the exact local failure agree; actual hosted acceptance after correction remains untested until explicit approval/application.

## Minimal reviewed migration — approval required

File: `supabase/migrations/20261107130000_restore_api_auth_schema_usage.sql`.

```sql
begin;
grant usage on schema auth to castly_api;
commit;
```

This is the only privilege change. It grants namespace access, not SELECT on Auth tables, CREATE on the schema, table-wide UPDATE, ownership, role membership or BYPASSRLS. It leaves UPDATE(status), RLS and SECURITY INVOKER unchanged. It adds no trigger-function EXECUTE grant or SECURITY DEFINER conversion. EXECUTE on `auth.uid()` is a separate prerequisite: it was explicitly verified as already allowed in the regression, and the function executes after the single USAGE grant. The migration intentionally does not add a function grant.

Applying the exact artifact twice locally succeeds and preserves one conversation after repeated acceptance. Do not replay historical migrations or use a broad `GRANT ALL` to correct this drift.

## Before / after — asserted by the same integration test

| Check | Before: revoke only auth USAGE | After: apply only reviewed USAGE grant |
|---|---|---|
| Booking/user SELECT | Allowed | Allowed |
| `auth.uid()` function EXECUTE ACL, inspected by OID | Allowed | Unchanged; direct execution succeeds |
| `auth` schema USAGE | Denied | Allowed |
| Relevant Talent booking SELECT FOR UPDATE | Succeeds | Succeeds |
| Direct `auth.uid()` invocation |42501, permission denied for schema auth | Authenticated synthetic Talent identity returned |
| Diagnostic probe | postgres_privilege_denied; identity/role unknown | ok; identity match, self account visible, role match true |
| Direct status UPDATE |42501; guard context identifies auth.uid initialization | Acceptance HTTP flow succeeds |
| Talent accept HTTP |403 / Not permitted at booking.accepted.update |200 / accepted |
| Atomic state on failed acceptance | requested; no conversation or acceptance notification | One authorized conversation after acceptance and retry |
| Client acceptance | Not part of broken-namespace phase |403 |
| Other actual Talent acceptance | Not part of broken-namespace phase |404; no state change |
| Outsider booking/conversation access | Not part of broken-namespace phase |404 |
| Talent reading another private account | Not part of broken-namespace phase | Zero visible rows |
| SELECT on auth.users | No new object grant | Denied |
| Table-wide booking UPDATE / UPDATE(updated_at) | Denied | Denied |
| UPDATE(agreed_rate) attempt | No new object grant | Denied |
| CREATE on auth | Denied | Denied |
| Guard SECURITY DEFINER | False | False |
| Booking/users/conversation RLS enabled | True | True |

The regression runs through the real Spring HTTP/controller/transaction code with the restricted castly_api database login. Only fixture setup, deliberate privilege removal and the candidate grant use the disposable local owner. They never run against hosted PostgreSQL. Each failure transaction rolls back; a finally block restores the local fixture prerequisite if an assertion fails.

## Regression location and validation

`backend/src/test/java/com/castly/BusinessApiTest.java`:
`authSchemaUsageAloneRepairsAcceptanceWithoutWideningBusinessPrivileges`.

Targeted test command (Java21):

```sh
cd backend
mvn -Dtest=BusinessApiTest#authSchemaUsageAloneRepairsAcceptanceWithoutWideningBusinessPrivileges test
```

Full verification uses `mvn clean verify`, then `CASTLY_TEST_SCHEMA_PATH=reconciliation mvn clean verify`, each preparing only named disposable Docker PostgreSQL fixtures. Cloud uses the existing offline Maven cache. Logs are committed under `artifacts/milestone5/auth-schema-usage-*.log`. No mobile code changed; mobile/browser/export checks were not rerun for this SQL prerequisite repair.

| Run | Result |
|---|---|
| Targeted before/after regression | PASS:1 test; all before/after assertions satisfied |
| Fresh-schema Java21 clean verify | PASS:83 tests; zero failures/errors/skips |
| Prepared/reconciled-schema Java21 clean verify | PASS:83 tests; zero failures/errors/skips |

Surefire reports an inherited-native-stdout warning from the existing fixture subprocess: `Prepared isolated castly_spring_test database (...)`. The dumpstream identifies that subprocess status line; it is not a test assertion failure. Both full Maven builds finish BUILD SUCCESS. The warning and exact results are preserved in the logs; unrelated fixture-output plumbing was not changed in this minimal repair.

## Hosted approval and verification boundary

Before approval, the owner can perform this **read-only** preflight through an approved privileged metadata connection:

```sql
select has_schema_privilege('castly_api','auth','USAGE') as auth_usage,
       has_function_privilege('castly_api','auth.uid()','EXECUTE') as uid_execute,
       has_table_privilege('castly_api','public.bookings','UPDATE') as table_update,
       has_column_privilege('castly_api','public.bookings','status','UPDATE') as status_update;
```

Expected affected baseline: auth_usage=false, uid_execute=true, table_update=false, status_update=true. If uid_execute is also false, that is a separate missing prerequisite requiring review; do not silently expand this migration. Keep the existing participant/role/transition/RLS checks.

**Wait for explicit approval.** Only then may the owner apply this single forward artifact through the approved migration process, verify auth_usage=true while other grants remain unchanged, and authorize a controlled booking acceptance test. That acceptance can mutate booking/conversation data and has not been performed against hosted Supabase by this task. Verify Client/other Talent denial and private-row isolation after application, using approved test accounts. Disable temporary probe instrumentation after verification. Do not automatically revoke the restored namespace prerequisite as a rollback: doing so deliberately recreates this failure.
