# Issue #1: reviewed forward-only reconciliation

**Do not run `supabase db push`, repair the migration ledger, replay historical migrations, provision hosted LOGIN, or run these mutation scripts on hosted Supabase without explicit owner approval.** This PR changes no mobile environment/backend selection. Preparation and cutover are separate approvals. No hosted SQL was executed for this work.

## Evidence and comparison limits

The owner's read-only baseline in GitHub issue #1 (2026-10-30) reports PostgreSQL 17.11, nine public RLS tables, only ledger version `20261027032615_index_messages_sender_and_talent_category`, no `castly_api`, no decision/cancellation RPCs, existing `complete_onboarding` callable by authenticated, no `api_*` policies, and `bookings_stamp`. The issue is the available hosted evidence, **not a complete schema dump**. No privileged hosted connection was available. The actual column/default/constraint/function/policy/grant/Storage definitions and pooler settings remain unverified. Preparation is gated on an owner's review of that evidence; the local fixture approximates the reported baseline using repository DDL and is explicitly not a hosted clone.

| Repository history | Reported hosted baseline | Reconciliation |
|---|---|---|
| `20261022000100` creates all tables/types/triggers/RLS/grants and onboarding | Tables, RLS, onboarding and booking stamp already exist; details unknown | Never recreate or mark applied. Inventory and verify structural equivalence first. |
| `20261022000200` creates private buckets/Storage policies | Exact bucket settings/policies not supplied | Inventory; require two private buckets and review owner/path/published-media rules. Do not replace Storage policies automatically. |
| `20261027120000` adds two SECURITY DEFINER booking RPCs and message-write policy | Both RPCs absent | Do not create compatibility stubs. Preparation uses Java plus invoker triggers/RLS. Cutover conditionally revokes only functions that actually exist. |
| `20261027140000` adds API role/policies/name snapshots, immediately revokes mobile writes and unconditionally revokes absent RPCs | API role/policies absent; mobile access still needed | **Do not apply this file to hosted baseline.** Split preparation from revocation; no old-RPC dependency. |
| New `20261030090000` hardens API booking/message triggers | API role not installed | Fresh repository-history path only; do not replay history to reach it. Preparation installs identical hardened bodies directly. |
| Hosted index migration | Only known ledger entry | Preserve ledger and existing indexes. Add only missing API query indexes after review. Index names alone do not prove equivalent definitions. |

## Inventory, backups and owner review

1. Owner takes a consistent database backup/PITR restore point, schema-only dump, role/ACL/function/trigger/policy/ledger inventory, and a separate Storage object/metadata backup. Keep all backups and connection services outside Git. Restore and test them on a disposable staging project. Do not include private account/message data or credentials in the PR.
2. Run **read-only** `01-inventory.sql` with `psql -X -v ON_ERROR_STOP=1 -f ...`, using an existing approved read-only/owner connection. Capture stdout and notices privately. It lists columns/defaults/nullability, constraints and validation, triggers and function bodies/security/search paths, indexes, RLS, table/column/schema ACLs, roles/memberships, sequences, publication membership, bucket settings and the actual ledger.
3. Compare all nine public tables with repository DDL: PK/FK/delete rules; enum labels; unique conversation pair and portfolio paths; booking defaults/protected fields; self/participant/published CHECK and RLS semantics; complete_onboarding and immutable-role/Auth-bootstrap logic; timestamp trigger bodies; matching types/nullability/defaults; no extra SECURITY DEFINER write paths. Review every permissive/restrictive policy (including PUBLIC) and all effective grants. PostgreSQL permissive policies combine with OR. Do not treat a name match as equivalence.
4. Review Storage policies and effective grants: talent-folder uploads/deletes, registered published portfolio reads, draft-owner access, private-media owner-only access, immutable upload names and private bucket flags. Realtime requires participant SELECT policies plus publication membership; publication membership alone does not authorize delivery. API role gets no Storage or Auth-directory access. Review `auth.uid()` implementation and both standard transaction claim formats.
5. Check existing data without changing it: orphan references, mismatched Client/Talent roles, duplicate conversation pairs, invalid profile/portfolio paths, unknown statuses, empty display names and conflicting pre-existing name snapshot columns. Resolve discrepancies via a separately reviewed additive artifact, not edits to historical migrations. Validate index definitions, check constraints and FK supporting indexes for actual workload. Extra unknown tables, policies, functions or grants need explicit disposition.

`structural-check.sql` enforces minimum known column/type/enum/PK/RLS/trigger/bucket/uniqueness prerequisites and rejects PUBLIC policies. It does **not** establish complete equivalence for unseen CHECK/FK/default/policy/function definitions. If it fails, stop and update the reviewed forward plan based on inventory; do not bypass it. No ledger repair is included or authorized. Even verified equivalence requires explicit approval before recording any migration-history change.

## Staging preparation (first approval)

After schema review, restore the actual snapshot to staging. The following are owner-run examples, not commands executed by the app:

```sh
PGOPTIONS='-c castly.reconciliation_schema_reviewed=issue-1-reviewed' \
  psql 'service=castly-staging-owner' -X -v ON_ERROR_STOP=1 \
  -f supabase/reconciliation/issue-1/02-prepare.sql
psql 'service=castly-staging-owner' -X -v ON_ERROR_STOP=1 \
  -f supabase/reconciliation/issue-1/04-verify.sql
```

Preparation is one transaction. It adds a NOLOGIN/noninheriting/nonowner/non-BYPASSRLS API role, bounded column grants, API-only RLS, name snapshots and query indexes; installs invoker booking/message triggers; and adds participant tables to an **existing** Realtime publication. It refuses unsafe role memberships/ownership. On hosted Supabase, the role-membership guard permits only the observed `castly_api` → `postgres` membership granted by `supabase_admin` with `admin_option=true`, `inherit_option=false`, and `set_option=false`; it rejects every other inbound or outbound membership. This is a narrowly scoped platform compatibility exception, not a blanket membership allowance. The admin option remains a platform-controlled administrative capability and must not be confused with application API privilege. Existing `bookings_stamp` is disabled only during the transaction's name backfill and immediately re-enabled, preserving historical timestamps. Existing nonempty name snapshots are retained. All other triggers remain active. A failure rolls back DDL, data and trigger state.

It **preserves authenticated onboarding and all current mobile business writes**. It removes unauthenticated onboarding EXECUTE, already absent in the repository baseline; authenticated access stays intact. It never creates missing decision RPCs, grants API access to old SECURITY DEFINER RPCs, deletes data, changes Storage policies, or writes the ledger. Existing API policies/triggers cause a safe duplicate-object failure rather than silently replacing unknown definitions; preparation is deliberately not a blind repeatable repair script.

The minimum checks expect a reviewed `users_immutable_role`, `castly_account_created` and `bookings_stamp`. If hosted names or bodies differ, reconcile them explicitly after inspection. Pre-existing snapshot columns must be TEXT NOT NULL. Unresolved empty names fail verification; do not invent names.

## Dedicated API connection and regression

After a separate approved role-provisioning operation on staging, the owner enables `castly_api LOGIN` and uses a secure password prompt/secret manager (`psql` `\password castly_api`) to set a strong unique password. Never commit it or send it to Expo. Do not grant role memberships, ownership, schema/database CREATE, sequence-wide rights, SUPERUSER, BYPASSRLS, replication, CREATEROLE, CREATEDB or entitlement writes. UUID defaults use `gen_random_uuid()`, so no sequence grants are needed; any different hosted default requires review. Check both inbound and outbound role memberships and effective PUBLIC privileges.

Use verified TLS (`sslmode=verify-full` and trusted root), `prepareThreshold=0`, and the documented session/transaction pooler username (`castly_api.PROJECT_REF` only where required; actual `current_user` must be `castly_api`). Never use owner, postgres/admin, service-role or JWT signing-secret credentials. Spring starts only with a restricted real database identity. Each business transaction binds `request.jwt.claim.sub`, JSON `request.jwt.claims` and role through transaction-local `set_config`; the connection itself stays `castly_api` so role-specific RLS/trigger checks apply. Review [backend setup](../../../backend/README.md).

Run local/staging API and actor regression before discussing cutover: onboarding/profile/portfolio/favourites; anonymous public reads and denied writes; client/talent/outsider private access; accept/reject/cancel/idempotency/terminal decisions; accepted-only conversations and messages; both send/cancel orderings; forged sender/status/rate rejection; Realtime participant SELECT and direct Storage compatibility. Full staging Supabase gateway/Storage/network checks still need the real platform.

The project's active signing algorithm is **not assumed**. Read-only `npm run test:jwt-config` returned HTTP 403 in Cloud. Owner must inspect Supabase Auth signing configuration and test a real development user's JWT with the exact issuer/audience. JWKS mode supports RS256/ES256; explicit `supabase-auth` mode handles HS256/overlap via the configured project's Auth verification endpoint with a publishable/anon key. Also enable leaked-password protection in the Supabase dashboard as the issue notes; this is a manual setting, not a SQL/app change in this PR.

## Separate cutover approval

Keep the current mobile configuration and fallback working until **all** API regression, real JWT/pooler/TLS, hosted Realtime, Storage and native tests pass, and an approved mobile release/cutover window is ready. This PR performs no switch. Test the intended API release using separate staging app configuration; do not change the existing app during preparation.

At the approved cutover window, stop/drain legacy business writes and follow the owner's coordinated mobile/API release plan. Owner runs:

```sh
PGOPTIONS='-c castly.reconciliation_cutover_approved=issue-1-cutover-approved' \
  psql 'service=castly-staging-owner' -X -v ON_ERROR_STOP=1 \
  -f supabase/reconciliation/issue-1/03-cutover.sql
PGOPTIONS='-c castly.reconciliation_verify_cutover=yes' \
  psql 'service=castly-staging-owner' -X -v ON_ERROR_STOP=1 \
  -f supabase/reconciliation/issue-1/04-verify.sql
```

Use separately approved hosted services only after staging succeeds. Cutover refreshes missing snapshots for bookings written since preparation, then revokes **all table and individual column business-write rights** from PUBLIC/anon/authenticated, including unexpected legacy grants. It revokes all existing overloads of the three old write RPCs conditionally; absence is valid. It retains ordinary SELECT for Realtime and all Storage grants/policies. Verify effective privileges and RLS with real participant and outsider tokens, then run the complete API regression again. No ledger entries are synthesized.

## Failure and rollback/restore strategy

- Before commit of either SQL transaction: any error rolls back all its changes. Verify trigger state and catalog inventory after failures; do not continue from a partial console selection.
- After preparation: legacy mobile still works. Disable/stop API traffic and, if owner-approved, disable API LOGIN and terminate only its active sessions. Retain additive columns/metadata; do not drop data to undo preparation. Compare captured schema/ACL snapshots and apply a reviewed forward repair if needed.
- After cutover: do not blindly grant broad writes or turn on the development fallback against the cutover database. Stop API writes, reconcile bookings/messages committed since the cutover, and restore only the exact pre-cutover business grants/RPC ACLs from reviewed backups if rolling the mobile release back. Verify policy/participant tests before reopening traffic. Storage and Auth are unaffected. This security-sensitive rollback requires a separate owner decision.
- For data corruption: validate PITR/database restore on staging, reconcile subsequent business data, and coordinate Auth/Storage restoration explicitly. A database rollback alone does not roll back uploaded objects or mobile releases. Do not automatically drop objects or overwrite backups.

## Local validation

`npm run test:reconciliation` creates only `castly-issue1-reconciliation-db` (Docker PostgreSQL 17, no published port) and disposable databases. It approximates the reported baseline with synthetic data, both absent/present RPC cases, tests refusal gates and schema drift, preserves existing names/timestamps/ledger/data, verifies the old M1 actor suite after preparation, and runs API/Storage/Realtime-read actors before/after cutover. It intentionally injects PUBLIC and column grants to prove revocation. No hosted URL is accepted by this runner. Remove the disposable container with `docker rm -f castly-issue1-reconciliation-db` after review.

The fresh repository-history path is separately covered by `mvn clean verify` and `npm run test:sql`. Historical migrations remain byte-for-byte unchanged. Staging against an actual Supabase clone, complete hosted inventory, real signing validation, strong hosted LOGIN/pooler configuration, password-protection setting and coordinated mobile cutover are unresolved owner actions, not locally validated claims.
