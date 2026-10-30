# Issue #1 — booking authorization and safe Supabase reconciliation

Validated 2026-10-30. Base: `feat/phase2-bookings-messaging` at `b4a8f72`. Review branch: `fix/issue-1-booking-security`.

## Implementation

- Booking decisions now normalize participant IDs to UUID and compare UUID-to-UUID. The existing JSON row mapper emits UUID strings, which masked the dependency on representation in older tests; tests now explicitly cover both JDBC UUID objects and serialized strings.
- Conversation creation and sending explicitly check that the caller is a participant, in addition to RLS. Outsiders receive 404 without disclosing private resources. Existing role/state/idempotency rules are retained.
- A new forward migration hardens the invoker booking trigger: participant and immutable account-role checks, allowed transitions, and protected-field immutability even if column grants are accidentally widened. It permits the existing stamp trigger to maintain updated_at and works with UPDATE(status), without table-wide UPDATE rights.
- A database message trigger locks accepted bookings in UUID order, matching Java send/cancel serialization. Direct API-role SQL cannot race cancellation past the accepted-booking boundary. History remains readable after cancellation; terminal bookings cannot reopen through the API.
- Transaction-local JWT identity binding was reviewed and tested across eight concurrent pooled connections, rollback and anonymous reuse. No ActorDatabase change was needed. Anonymous public GETs work; private routes/writes reject missing or malformed tokens.
- JWT tests cover actual RSA/EC verification, forged signatures, issuer/audience/expiry/role, missing expiration, bad subject/future nbf, JWKS HMAC downgrade rejection, explicit legacy-mode provider verification, asymmetric overlap, wrong returned identity and provider errors. These do not prove the hosted project's active signing configuration.

## Reconciliation artifacts

[Owner plan and commands](../../supabase/reconciliation/issue-1/README.md) include read-only inventory, structural gates, separate preparation/cutover approvals, backups, verification and rollback/restore strategy. No historical migration or migration ledger is rewritten.

Preparation preserves the current direct mobile business writes and authenticated onboarding. It installs the dedicated NOLOGIN API role/policies and invoker guards, adds/backfills snapshots without rewriting historical timestamps, retains nonempty snapshots, and preserves Storage rules. It does not create the missing privileged decision RPCs. Cutover is a separate gated artifact: it refreshes snapshots for intervening legacy requests and revokes table AND individual-column business writes from PUBLIC/anon/authenticated, plus old RPC execution only where those functions exist. Realtime SELECT and Storage are retained.

The issue reports a hosted ledger containing only `20261027032615_index_messages_sender_and_talent_category`, absent API role/policies/decision RPCs, existing onboarding and nine RLS tables. That evidence is not a complete schema dump. **Actual hosted columns/constraints/defaults/ACLs/function bodies/Storage policies have not been independently verified here.** The scripts refuse unreviewed execution and obvious drift; the owner must supply/review the full read-only inventory before any approved staging/hosted reconciliation. Local fixtures approximate the reported baseline rather than pretending to be the hosted database.

## Test results

| Command/check | Result |
|---|---|
| `mvn clean verify`, JDK 21, fresh repository migration path | PASS — 66 tests, zero failures/errors/skips |
| `CASTLY_TEST_SCHEMA_PATH=reconciliation mvn clean verify`, prepared schema path | PASS — 66 tests, zero failures/errors/skips |
| `npm run test:reconciliation` | PASS — 286 SQL assertions/catalog gates across RPC-absent and RPC-present fixtures; eight expected approval/drift/data-quality rejections |
| `npm run test:sql` | PASS — 25 authorization assertions on fresh migration history |
| `npm run typecheck` | PASS |
| `npm run lint` | PASS |
| `npm test` | PASS — 27 tests |
| `npm run check:client-env` | PASS |
| `npm run test:backend` | PASS — real Spring HTTP/JWT/JWKS/PostgreSQL and rendered browser |
| `CASTLY_TEST_SCHEMA_PATH=reconciliation npm run test:backend` | PASS — same real API/browser workflow against prepared schema |
| `npm run test:legacy-backend` | PASS — intercepted Auth/REST/Storage contract, Metro development fallback |
| `npm run test:browser` | PASS |
| `npm run test:portraits` | PASS |
| `npm run test:ui` | PASS — ten screens at three widths, 30 captures |
| `npm run test:screenshots` | PASS — 30 PNGs decoded with correct dimensions and nonblank pixels |
| `npm run test:accessibility` | PASS — touch targets, text scaling, three-width profile/footers/date/unread checks |
| Expo Android/iOS/web bundle export, mock mode | PASS — not native builds |
| Expo connected-mode web export with local fixture values | PASS |
| `npm run test:jwt-config` | BLOCKED — `Public JWKS verification failed: curl: (22) The requested URL returned error: 403` |
| Native device/emulator, hosted Realtime/Storage/pooler, actual hosted-schema equivalence | NOT TESTED — owner/staging access required |

Maven was run from backend with the workspace JDK21/Maven and `-o -Dmaven.repo.local=/workspace/.m2/repository` using cached dependencies. Docker PostgreSQL 17 is real; Auth/Storage platform SQL is a local test contract. The browser's Auth/JWKS endpoint is a fixture; Spring business HTTP, signature decoding and PostgreSQL are real. Test-container owner credentials are used only for disposable DDL/fixtures; API requests connect as restricted castly_api. No hosted database was mutated.

The 45-case transition matrix covers five starting statuses, Client/Talent/outsider and all three actions, including acceptance, rejection, both cancellation roles, idempotency and terminal conflicts. Two deterministic locking tests prove both send-first and cancellation-first serialization, including direct API-role inserts. Reconciliation tests verify legacy M1 compatibility after preparation, data/name/timestamp/ledger preservation, privilege revocation including injected PUBLIC/column grants, accepted-only API messages/conversations, private accounts/favourites/entitlements, portfolio ownership, anonymous discovery, participant Realtime SELECT and direct Storage compatibility. Trigger bodies are checked to match between the fresh-history and reconciliation paths.

An initial local run found a stopped reusable Docker container and a test assertion using JdbcClient.single() for a nullable auth.uid(). The fixture now restarts the test container; the assertion explicitly coalesces anonymous identity. Final runs pass. Node's existing module-type warning remains; PostgreSQL warns that the plain local test container is not configured as a live logical-replication gateway. Neither warning is treated as proof of hosted/native behavior.

Raw results: [logs](logs/). Fresh API captures: [accepted booking](screenshots/accepted-booking-390x844.png), [conversation](screenshots/conversation-390x844.png), [verified PNG dimensions/hashes](screenshots/manifest.json). Existing approved UI captures were restored after regression to avoid unrelated screenshot churn.

## Delivery and remaining manual actions

Implementation commit `d53b7d0` was pushed to `fix/issue-1-booking-security`. GitHub CLI API/auth access returns `Forbidden` (including an elevated retry); public issue HTML and Git transport work. **PR creation is blocked**: `gh pr create` returned `Post "https://api.github.com/graphql": Forbidden`, and the REST create-pull-request fallback returned `Post "https://api.github.com/repos/agarwalshubham1311-cmd/castly-mobile/pulls": Forbidden`. No PR number was issued. [Open the correct base/head comparison to create the PR](https://github.com/agarwalshubham1311-cmd/castly-mobile/compare/feat%2Fphase2-bookings-messaging...fix%2Fissue-1-booking-security?expand=1). The prepared description is [pull-request.md](pull-request.md).

Before rollout the owner must review the complete schema inventory, restore/test a staging snapshot, approve preparation, provision a strong dedicated role LOGIN and verify pooler/TLS identity, verify actual JWT signing mode with a real user, validate hosted Realtime/Storage/native clients, and enable leaked-password protection manually in the Supabase dashboard. Only then should the owner approve a coordinated mobile release and the separate revocation cutover. No mobile application/configuration switch, deployment, hosted mutation, payment work or main merge is part of this change.
