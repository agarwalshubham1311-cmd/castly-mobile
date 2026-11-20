# Concurrency integration and Phase 6 worker security checkpoint

Reviewed 20 November 2026. **Local validation does not authorize production activation.** No push, milestone/main merge, hosted migration, provider send, deployment, or feature activation was performed. Mobile UI and Phase 7 are unchanged.

## Git integration

The explicitly fetched milestone remote was `3c12b515f5ba2e6ef67d0089d0fd49ea6c99a175`, exactly the concurrency fix baseline. The older milestone checkout at `d065bf5` was left alone. Source `fix/backend-concurrency-tests` at `880dfcd0d21e0ec90fb70605dd43077f6eaca2ca` contained only `BusinessApiTest.java`, `ConcurrentBoundary.java`, `ConcurrentBoundaryTest.java`, and `docs/testing/backend-concurrency-fixes.md`.

An isolated `/workspace/castly-worker-security` worktree on `integration/concurrency-phase6-worker-security` was created from the fetched milestone. The targeted cherry-pick produced **`f7718006ab156b189a78f186cb145772eeba81a3`**, without conflicts. Its backend suite passed all 165 tests before worker changes. No milestone or main branch was moved. Phase 7 remains at `15b96eecf77333dddac1e722a9a996d03fbd3287`.

Additional review changes: `EngagementWorker.java`; new `WorkerSecurityIntegrationTest.java`; new `supabase/tests/phase6-worker-security.sql`; registration in `scripts/test-sql.cjs` and `scripts/test-reconciliation.cjs`; this report and sanitized local identity evidence. No historical migration changed. The final local hardening commit is the commit containing this report; obtain its exact SHA with `git log -1 --format=%H` on the integration branch.

## Findings and corrections

1. **Worker identity preflight was incomplete.** `EngagementWorker` checked `current_user`, role attributes, membership and table ownership. In a disposable owner-authenticated connection, both `SET ROLE` and `SET SESSION AUTHORIZATION` could make that role check pass. The latter also changes `session_user`; neither changes `pg_stat_activity.usename` for the backend. The constructor now requires all three to equal `castly_engagement_worker`, preserving existing attribute/membership/ownership checks. Both negative cases failed before correction and pass afterwards. This is defense in depth: the existing configuration factory already rejects administrator usernames; this finding does not establish a remotely exploitable API route. Supabase session-pooler compatibility remains a required hosted check, with no fallback if it fails.
2. **Direct scheduling ignored the worker switch.** `tick()` already returned while disabled, but `schedule()` only checked engagement. A direct call created scan state with worker=false. It now requires both flags. The negative regression failed before correction and passes afterwards. Paused unsubscribe/control-plane functionality remains available.
3. **Initialization and execution are intentionally different.** `WorkerConfiguration.ConnectionPresent` initializes a dedicated connection when worker=true **or** a worker URL is configured. That permits signed unsubscribe/webhook controls while outbound work is paused. Blank URL + worker=false creates neither worker nor Tick. Configured paused worker passes identity preflight but does no scheduled work. No flags/defaults were changed.
4. **No privileged datasource fallback was found.** `WorkerConfiguration.engagementWorker()` accepts only the dedicated username (optional project suffix), requires a password, calls `DatabaseConfiguration.validate()`, creates its own two-connection pool, closes it on constructor failure, and declares bean destruction with close. URL user/password/options overrides are rejected; remote TLS still requires verify-full. Invalid-password tests assert actual PostgreSQL `28P01`, not just any startup exception. Captured negative startup logs exclude password sentinels and local fixture passwords. This verifies tested paths, not arbitrary JDBC debug/TRACE logging configurations.
5. **Purpose-limited access is intentional, not tenant impersonation.** The worker can process outbox rows across recipients and read opted-in preference metadata for scheduling. Booking/conversation columns are restricted and source-scoped; token/contact access requires a queued recipient/source. It cannot read account records, message bodies, booking prose, payments or private dispute evidence, manufacture jobs, retarget recipients/business sources, or change booking status. Forging a transaction JWT subject cannot widen that scope. No RLS, role, grant, trigger or migration change was necessary.

## Exact review locations

- `backend/src/main/java/com/castly/engagement/WorkerConfiguration.java`: `ConnectionPresent.matches`, `engagementWorker`, `phase6Tick`, `Tick.run` (lines 13–70).
- `backend/src/main/java/com/castly/engagement/EngagementWorker.java`: constructor identity preflight (lines 42–57); `tick` claim/lease and SKIP LOCKED (60–99); provider send/outcome accounting (257–288); `apply` retry/dead-letter/uncertain transitions (304–340); `schedule` disabled guard and cursor scan (472 onward). Scheduling errors currently have a fail-closed catch, not an operational error metric (around 533).
- `backend/src/main/java/com/castly/api/DatabaseConfiguration.java`: `validate` rejects URL identity overrides and requires verified remote TLS (45 onward).
- `supabase/migrations/20261112120000_phase6_engagement.sql`: role preflight and invoker helpers; worker column ACLs, purpose RLS, function revocations/grants (200–236). Unmodified SHA-256: `e96f83ea35c12200a194c384feebf2e550762b64078a3f3b7bd2d7875d74595a`.
- `supabase/tests/phase6-worker-security.sql`: 37 explicit local assertions, all transactional/rolled back. Existing legacy definer RPCs must be migration-owned with pinned empty search_path; none is worker-executable. Phase 6 helpers must all be invoker. Tests include actual denials with SQLSTATE 42501, RLS isolation and permitted queue claiming. SQL role simulation uses SET SESSION AUTHORIZATION to avoid retaining owner SET ROLE rights; it is **not** evidence of password authentication.
- `backend/src/test/java/com/castly/engagement/WorkerSecurityIntegrationTest.java`: 11 tests use a separately password-authenticated local JDBC pool. Role escalation probes, owner-session disguises, actual role attributes, ACL/RLS denials, valid/invalid factories, paused controls, cleanup and connection-loss recovery are exercised. Only outbound delivery and discovery dependency are mocks. `local-identity.json` records actual local authenticated/effective/session identity and role facts, with no destinations or connection details.
- `backend/src/test/java/com/castly/BusinessApiTest.java`: `phase6BookingOutboxIsAtomicDedupedAndRecipientOnly` (3663), worker retry/receipt/invalid-token (3720), concurrent workers/ambiguous dispatch (3757), consent/block (3783), signed webhook/unsubscribe/replay (3914), digest dedupe/block recheck (4023), quiet-hours/dead-letter (4117), caps (4159), block serialization (4194).

## Validation evidence

Environment: Java 21.0.12.1, Maven 3.9.11, Node 24, disposable Docker PostgreSQL 17.11 on Linux. Windows was not used in this task; the commands below are PowerShell compatible. Existing concurrency assertions were preserved. No browser/native E2E was required or run.

| Check | Result |
| --- | --- |
| Integrated concurrency commit, before hardening: `mvn test` | PASS — 165 tests |
| New negative regressions, before hardening | Expected FAIL — 2 failures: disguised owner session accepted, disabled direct scheduler wrote state |
| `mvn '-Dtest=WorkerSecurityIntegrationTest' test` after fixes | PASS — 11 tests, zero failures/errors/skips |
| Complete backend, fresh migration fixture | PASS — 176 tests, zero failures/errors/skips |
| Complete backend, reconciled legacy fixture | PASS — 176 tests, zero failures/errors/skips |
| `npm run test:sql` | PASS — all existing SQL suites plus 37 worker assertions |
| `npm run test:reconciliation` | PASS — 466 assertions/catalog gates across both legacy variants, including 74 worker assertions (37 each) |
| Historical Phase 6 migration hash | PASS — unchanged |

Connection-loss tests terminate only their own local worker backends. Idle sockets recover on later ticks; no duplicate delivery occurs. A loss after provider acceptance leaves the committed claim quarantined as uncertain after lease expiry; the mock provider is called once. The test allows later scheduled invocations to evict stale sockets; advancing its domain clock does not advance Hikari's real-time validation window. No automatic resend or production blind retry was added.

Test harness corrections made during development: include Phase6Signals for the injected mocked discovery service, assert exact startup causes to prevent false positives, use session authorization for SQL permission probes rather than an owner session's SET ROLE, and include the authenticated-login alias in evidence SELECT. Initial harness failures were corrected, without relaxing production security expectations. Both full runs emitted Byte Buddy/native-stream warnings. Surefire's dumpstream identifies direct fixture-preparation stdout via the existing inheritIO child process; both runs exited successfully with all reports passing. No warning was suppressed. Successful exit and Surefire results determine the test result.

## Phase 6 release gates

| Gate | Status | Evidence or remaining requirement |
| --- | --- | --- |
| Local authenticated identity, attributes, membership, ownership, invalid config | PASS | Real JDBC login and negative probes, 11 new tests |
| Local ACL/RLS/function boundary, fresh and reconciled SQL | PASS | 37 worker assertions per fixture, SQL/reconciliation commands |
| Local claim/idempotency/concurrency/retry/backoff/dead-letter/disabled/recovery | PASS | Existing BusinessApiTest cases plus new interruption and pause tests; mock delivery only |
| Hosted migration equivalence and actual session-pooler identity | BLOCKED | No authorized hosted inventory or worker login provisioned/tested |
| Email verification policy | BLOCKED | Trusted snapshot/90-day send limit exists; no automatic Auth email-change feed. Owner must approve policy or require feed |
| Provider/domain readiness | NOT TESTED | No provider-managed sandbox; SPF/DKIM/DMARC, mailbox delivery and abuse/consent process unverified |
| Local unsubscribe/webhook signature, replay and suppression | PASS | Existing tests plus new paused-control test |
| Deployed callback reachability and real provider webhooks | NOT TESTED | Requires approved nonproduction provider/environment |
| Native push, token lifecycle on hardware, hosted Realtime | NOT TESTED | Requires owned EAS/FCM/APNs and actual devices |
| Production monitoring and alert ownership | BLOCKED | Finite attempt/outcome metrics exist; queue age, dead/uncertain backlog, scheduler failures, alerts and runbook are not provisioned |
| Hosted secrets/rotation/least-privilege provisioning | BLOCKED | Requires owner-controlled inventory and approval; local test secrets are synthetic |
| Security/load/capacity/soak review | NOT TESTED | Functional denial tests are not an independent security review or performance validation |
| Rollback/restore | NOT TESTED | Flag-off strategy documented; staging restore/DR and callback continuity not exercised |

No confirmed failing regression remains after the fixes and both complete backend runs. **Phase 6 is not production-ready.**

## Reproduction in Windows PowerShell

Run only in this dedicated integration checkout, with Java 21, Maven, Node and Docker Desktop Linux containers installed. Scripts create/drop named disposable databases and synthetic roles; never point them at hosted infrastructure. Other Java/SQL runs sharing these fixture containers must not run concurrently.

```powershell
git status --short
git diff --check
docker info
# Start pre-existing local SQL fixture containers if stopped; the runners create absent ones.
docker start castly-phase2-policy-db
docker start castly-issue1-reconciliation-db
Push-Location backend
try {
    mvn '-Dtest=WorkerSecurityIntegrationTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Worker security regression failed' }
    $env:CASTLY_TEST_SCHEMA_PATH = 'repository'
    mvn test
    if ($LASTEXITCODE -ne 0) { throw 'Fresh backend suite failed' }
    $env:CASTLY_TEST_SCHEMA_PATH = 'reconciliation'
    mvn test
    if ($LASTEXITCODE -ne 0) { throw 'Reconciled backend suite failed' }
} finally {
    Remove-Item Env:CASTLY_TEST_SCHEMA_PATH -ErrorAction SilentlyContinue
    Pop-Location
}
npm run test:sql
if ($LASTEXITCODE -ne 0) { throw 'SQL security failed' }
npm run test:reconciliation
if ($LASTEXITCODE -ne 0) { throw 'Reconciliation failed' }
git diff --check
```

If fixture containers do not exist, omit their docker start commands; the runners create them. Do not reuse a concurrently modified checkout or hosted credentials. These are reproduction commands, not deployment instructions.

## Activation checklist — separate approvals required

1. Review this local integration diff and commit; approve before push or milestone merge. Confirm Phase 7 remains unchanged.
2. Obtain read-only hosted ledger/catalog/source-hash inventory; review exact prerequisite migrations and Phase 6 migration equivalence, role collision and function ACLs. No unrestricted db push, historical repair or automatic startup migration.
3. Rehearse the reviewed SQL and application against a representative isolated restore with timing/locking/capacity and recovery evidence. Apply hosted SQL only with explicit owner approval.
4. Provision a dedicated least-privilege worker login/secret using the approved session-pooler endpoint and existing verified TLS/CA setup. Verify effective, session and backend-authenticated identity in the deployed process. A mismatched pooler login must block startup; never weaken the guard or substitute administrator/service-role credentials.
5. Approve server-only secrets, custody/rotation, mailbox enrollment/reverification policy, retention/privacy/export/deletion, provider sandbox and sending-domain DNS. Keep unsubscribe/webhook controls and signing keys usable during pauses.
6. Provision approved native push builds and perform hardware, offline/account-switch, permissions and recovery tests. Test signed callbacks against the provider-managed sandbox and confirm recipient consent/caps/block behavior.
7. Set up queue-age/dead-letter/uncertain/scheduler monitoring, ownership and incident/DR runbooks; complete load/security review and staged rollback exercise.
8. Only after separate deployment approval, ship with existing flags false/providers disabled. Smoke booking, acceptance/conversation/messages, trust/private evidence, discovery and subscriptions. Approve a small internal consented cohort before any provider selector or worker activation. No real sends are authorized by this report.
9. Rollback means outbound/worker flags off and providers disabled, preserving management/privacy routes, configured control connection and callback keys. Preserve ledger/queue and reconcile uncertain sends with provider before any retry; do not blindly replay them or drop tables.

Phase 7: review approved integration into its ancestry in a separate worktree only after approval, then rerun admin authorization/audit and worker-boundary tests. Phase 8: complete independent security review, performance/soak, observability/DR, native-device and hosted/provider validations. No Phase 7 or Phase 8 implementation was started here.
