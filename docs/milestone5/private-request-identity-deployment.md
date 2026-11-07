# Castly private request identity fallback — deployment report

2026-11-07. Branch: `feat/milestone5-booking-messaging-notifications`. **Prepared and tested locally; no hosted migration, deployment, policy/grant change or booking write has been performed.** No mobile UI, JWT keys, credentials, TLS/network settings, payment or unrelated application feature changed.

## Result and implementation scope

The fallback removes the Castly business API's dependency on resolving `auth.uid()` as the restricted `castly_api` role. It works while that role has **no USAGE on schema auth**. The previous grant-only candidate is superseded for this rollout; do not apply that candidate or replay historical migrations against hosted Supabase.

New forward-only migration: `supabase/migrations/20261107150000_private_request_identity.sql`. Historical migration files are unchanged.

- Creates the application-managed `private` namespace if absent. Its owner is the trusted migration role, **not** the runtime API role. The API gets USAGE only, never CREATE/ownership. Existing namespace owner/ACL/population conflicts abort for review instead of silently exposing unrelated objects.
- Creates `private.request_user_id()`, **STABLE SECURITY INVOKER**, with empty search_path and explicit pg_catalog settings reads. Grants EXECUTE only to castly_api, revoking PUBLIC/anon/authenticated execution. Ordinary authenticated users cannot use the helper as an RPC or obtain private-schema access. Do not add private to PostgREST exposed schemas.
- Substitutes the identity call in the existing **20 API-exclusive policies** in the tested baseline. Uses ALTER POLICY on the actual stored predicates rather than replacing them with simplified predicates: names, roles, commands, permissiveness, USING/WITH CHECK logic and extra conditions remain. The other **27 policies** are unchanged, including Storage and authenticated/Realtime policies.
- Substitutes only the identity call in the existing booking-transition and message-send guard bodies. It verifies these are invoker PL/pgSQL trigger functions and preserves owners, function settings, ACLs, protected-field/role/participant checks, transition rules and lock order. Shared policies, unexpected guard shapes and unsupported additional auth dependencies stop the migration for review.
- Updates only the API's diagnostic and notification SQL calls that directly named auth.uid(): `BookingUpdateDiagnostics`, `NotificationController`, `NotificationService`. Existing endpoints, response behavior and feature flags are retained.

No new SECURITY DEFINER function, service_role credential, BYPASSRLS, authenticated membership, role elevation, table-wide UPDATE or broader table/column grant is introduced. The fallback does not recreate or change the pre-existing owner-run Auth bootstrap/notification functions; those are outside this invoker/API identity change. Existing Supabase Auth, Storage and Realtime identity paths remain untouched.

## Identity semantics and trust boundary

Spring verifies JWT signatures, pinned issuer, audience, time validity, allowed role and UUID subject using the existing resource-server configuration. This has not been replaced with trust in a client-supplied account ID. HTTP request bodies cannot choose the database actor.

`ActorDatabase.as()` already uses one TransactionTemplate and transaction-bound JdbcClient. It binds scalar `request.jwt.claim.sub`, JSON `request.jwt.claims` and request role using **set_config(..., true)** for every operation, including anonymous requests. No session-global identity or role switch was added. The helper reads those settings in the same transaction as the status UPDATE and database role lookup.

The helper supports either existing subject format. If both provide a subject, they must agree. Missing/empty settings return NULL, so private-row RLS comparisons do not match. Malformed UUID/JSON, a non-object JSON context, a non-string subject or conflicting subjects raise42501 with the fixed message `Invalid request identity context`; no claim values appear in that message. The empty search_path prevents caller-controlled function/type lookup from influencing identity parsing.

**The helper does not verify JWT signatures or prove that a GUC was SET LOCAL.** Spring's verifier and binder enforce those properties. PostgreSQL permits a database login to set custom settings; therefore the dedicated API connection credentials remain a trusted server boundary and must never be distributed to mobile clients or arbitrary SQL clients. The helper is a read-only identity accessor, not a substitute authentication service.

## Reproduction and regression evidence

Both Java fixture paths revoke auth schema USAGE **before applying the fallback**, and retain that denial for every business test. The prepared/reconciled path applies the fallback without applying the earlier auth-USAGE repair candidate. Fresh-history fixtures retain historical files unchanged, then explicitly reproduce the hosted denial before the new migration. This is not a claim that replaying historical managed-auth grants is appropriate for a fresh hosted project.

| Check under restricted castly_api | Result |
|---|---|
| Direct auth.uid() invocation |42501: namespace access denied |
| private.request_user_id() and diagnostic probe | Succeed; correct verified subject and visible database role |
| Valid Talent acceptance |200; one conversation; repeat acceptance reuses it |
| Client / other Talent acceptance |403 /404 |
| Outsider conversation access or message send |404 |
| Accepted participant message send |201 |
| Another user's private account | Zero visible rows |
| Anonymous private access | No matching booking/user rows |
| Malformed bearer token |401 through the HTTP security filter; forged signatures separately rejected by decoder tests |
| Auth table access | Denied |
| Table-wide booking UPDATE, private CREATE, role membership/BYPASSRLS | Remain denied/absent |
| Helper execution by anon/authenticated | Denied by ACL; private namespace not usable |
| Commit, rollback, concurrent pooled users | Identity does not leak across transactions |
| Invalid/conflicting scalar and JSON subjects | Fail closed with42501; following anonymous transaction stays anonymous |

The catalog preservation regression captures all public/Storage policies and both guards **before** the migration. Afterward it compares every field against the old metadata with only the permitted identity-call substitution. This verifies that additional predicate conditions, role scopes, guard bodies, owners/settings and ACLs were not silently removed.

The prior one-grant regression is superseded by the no-auth-USAGE fallback regression; its original reproduction, SQL candidate and logs remain in historical commits/artifacts. Existing transition-matrix, immutable-field, conversation rollback, participant messaging, notification privacy, parameter-binding and pool-isolation regressions remain.

## Validation results

| Command | Result |
|---|---|
| Java21 `mvn clean verify`, fresh fixture | PASS:86 tests; zero failures/errors/skips |
| `CASTLY_TEST_SCHEMA_PATH=reconciliation mvn clean verify` | PASS:86 tests; zero failures/errors/skips |
| JWT verification suite within each full run | PASS:9 tests, including signature/issuer/audience/expiry/role/subject and legacy verification failures |
| `npm run test:sql` | PASS:37 existing RLS/policy assertions with the new forward migration |
| `npm run lint` | PASS |

Cloud used JDK21, Maven3.9.11 and the existing offline dependency cache. Commands used `mvn -o -Dmaven.repo.local=/workspace/.m2/repository -f backend/pom.xml clean verify`; the second run adds the schema-path environment variable. SQL checks used a named disposable Docker container and explicitly unset SUPABASE_TEST_DATABASE_URL. That SQL container was initially stopped; it was started locally and the rerun passed. No hosted URL was used.

The existing fixture's inherited subprocess stdout causes a Surefire native-channel warning; dumpstream identifies the fixture's `Prepared isolated ... database` status line. Both builds finish BUILD SUCCESS, with no skipped or failed tests. Logs are in `artifacts/milestone5/private-identity-*.log`.

Authentication/JWKS responses in automated tests are local fixtures. Hosted end-to-end acceptance, hosted Realtime, Windows network/suspend behavior and native devices were not exercised. Mobile code is unchanged, so browser/export suites were not repeated for this backend/database-only fallback.

## Proposed deployment sequence — explicit approval required

1. Review the single new migration against a read-only catalog inventory of the actual hosted baseline. Confirm castly_api is a dedicated nonprivileged login with no memberships; inspect private namespace ownership/contents/ACLs if it exists, API policy scopes, both guard definitions/modes and existing migration ledger. A private helper-name collision or shared namespace/policy requires separate review, not automated takeover. Keep private outside exposed API schemas.
2. Obtain approval for **this fallback artifact** and an approved staging copy first. Do not apply the older `20261107130000_restore_api_auth_schema_usage.sql` candidate, replay older migrations, repair/rewrite existing hosted ledger entries, grant auth namespace access or change managed Supabase objects. This forward migration itself neither grants nor revokes auth schema access. **Do not run an unrestricted `supabase db push`: the repository still contains that superseded, unapproved grant-only proposal, and batch application could execute it.** The owner must approve a workflow selecting only the reviewed fallback and recording its new version normally, with explicit handling of the unapplied superseded proposal. No ledger repair/mark-applied command is supplied or executed here; historical files remain untouched as requested.
3. Coordinate an approved maintenance window for the server/schema change. Drain/stop the existing development/staging API traffic, apply only the reviewed fallback migration as the trusted migration owner, and verify the helper ACL/mode, policy/guard metadata and auth-USAGE denial. It is one transaction: a failed preflight or DDL error rolls back the whole migration.
4. Deploy/restart the matching Spring artifact with existing verified JWT settings, dedicated login, TLS certificate/verify-full and environment. Schema must be ready before this binary serves requests because its notification/probe SQL now calls the private helper. Older binaries can still issue auth.uid() SQL, so do not treat the schema change alone as a complete rollout. There is no mobile switch or UI deployment.
5. With approved staging test accounts, verify anonymous discovery/private denial; Client creation; Talent acceptance, rejection and cancellation; one conversation; participant messages; private notification counts/read markers; unauthorized account/bookings/messages access; and concurrent user/rollback isolation. Confirm current_user remains castly_api and auth schema USAGE remains denied. A hosted acceptance test writes business rows and requires its own approved test execution; none was performed here.
6. Disable temporary booking diagnostic probes after verification. Retain sanitized reason/reference logging. Expand beyond staging only through the normal owner approval process.

### Rollback limits

Do not drop private with CASCADE, drop policies, disable RLS, grant auth access, or convert guards to SECURITY DEFINER as a rollback. Before commit, PostgreSQL transaction rollback removes partial migration changes. After application, restoring an older binary alone can reintroduce auth.uid() failures. If validation fails, stop traffic and use an owner-reviewed coordinated schema/code restore from the captured baseline; there is no automatic destructive down migration. Never log settings contents, JWTs, credentials or private row data during verification.

**Current release state: implementation and local regression gates pass; hosted migration, deployment and business verification await explicit approval.**
