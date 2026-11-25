# Phase 7 synchronization and M7.1 review checkpoint

25 November 2026. This is a local code-review checkpoint, not deployment approval. No branch was pushed, no milestone/main branch was changed, and no hosted Supabase, Render, Expo, provider, or production configuration was touched. No real notifications were sent. All existing feature flags/providers retain their defaults.

## Repository inspection and integration

All branches were explicitly fetched with `git fetch origin 'refs/heads/*:refs/remotes/origin/*'`; the clone's normal fetch refspec tracks only main. Existing Phase 7 local and remote heads both matched `15b96eecf77333dddac1e722a9a996d03fbd3287` with zero divergence and a clean working tree. All other existing worktrees were also clean and were preserved.

The fetched milestone head is **`78791d2dc1d53646710501faac147c3217c18870`**. It contains both `f7718006ab156b189a78f186cb145772eeba81a3` (concurrency tests) and `268ca579c2ca1246ff4d29e3c0c3623ed8f9f147` (worker hardening). There were no additional production configuration changes in the milestone diff from the previous shared base `3c12b51`.

An isolated worktree `/workspace/castly-phase7-integration` and branch **`integration/phase7-milestone-sync`** preserve the original Phase 7 checkout. Local merge commit **`ca0d1a5331fc74ef30ea9d7d197a3ceae5057698`** has parents `15b96ee` and `78791d2`. One conflict in `scripts/test-reconciliation.cjs` was resolved by retaining the Phase 6 worker-security suite followed by the existing admin migration and RBAC suite. Both now execute in each reconciliation variant. No security test or authorization check was dropped. The original Phase 7 branch, milestone and main remain unmoved.

## Earliest unfinished milestone and implementation

M7.1 already had implemented authentication, RBAC, audit, pages, migrations and substantial positive/negative tests. Its existing checkpoint explicitly says **“Explicitly approve M7.1 before M7.2 implementation”** in [phase7-milestones.md](../phase7-milestones.md). This continuation re-audited that implementation before treating it as complete. A confirmed M7.1 cookie-boundary defect required correction. M7.2–M7.4 were not started; no completed functionality was duplicated.

**Root cause:** `src/lib/server.ts` chose the production cookie prefix using the raw `CASTLY_ADMIN_LOCAL_TEST` flag, but chose Secure/HTTPS behavior only after checking that all three origins were exact loopback URLs. With the test flag true and remote or mixed origins, cookies remained Secure but lost their `__Host-` prefix. This did not bypass Spring JWT/MFA/session checks, but weakened the intended browser host-cookie boundary under misconfiguration.

**Fix:** `src/lib/security.ts:adminCookiePolicy` now supplies both prefix and transport policy from the same validated local-fixture decision. All production cookies retain Secure and `__Host-` unless explicit fixture opt-in and all three exact HTTP loopback origins are present. Trusted-origin parsing and HTTPS rejection remain in `configuration()`; no remote HTTP allowance was added. HttpOnly, SameSite Strict, path=/, bounded expiry and no browser token storage remain unchanged. Invalid/missing/mixed/credentialed/path-suffixed or hostname-lookalike fixture origins cannot select the cookie exception. No migration or new flag is needed.

The new regression failed against the extracted original behavior (`prefix: ''`, expected `__Host-`) and passes with the correction. This is a configuration hardening finding, not evidence of a demonstrated authorization exploit. Actual browser Secure/host-cookie behavior over deployed HTTPS still requires approved staging validation.

Changes after the local merge:

- `admin-dashboard/src/lib/security.ts`: shared cookie-policy decision.
- `admin-dashboard/src/lib/server.ts`: use that decision for cookie names and trusted transport validation.
- `admin-dashboard/tests/security.test.ts`: two multi-case policy regressions, including each origin independently invalid and valid local/default production/development behavior.
- `admin-dashboard/tests/admin.spec.ts`: real BFF/Spring negative flow for guest and MFA-authenticated non-admin, missing-origin CSRF, fixed proxy paths, no admin session cookie issuance and rejection of a deliberately forged session cookie. An optional screenshot directory keeps historical reference artifacts untouched.
- This report and checkpoint links in the existing architecture/milestone documents. No dashboard redesign or mobile UI change.

## Milestone completion matrix

| Milestone | Completed | Partial / missing | Security and tests | Dependencies / blockers |
| --- | --- | --- | --- | --- |
| **M7.1 Admin Authentication and RBAC** | Separate Next App Router/TypeScript/Tailwind dashboard; Supabase identity through cookie BFF; Spring-derived granular six-role permissions; AAL2/TOTP/recent-MFA checks; short version/Auth-session-bound sessions; invitations/activation; role changes/revocation; append-only transactional audit; quotas/fanout; permission-aware shell; cookie correction in this checkpoint | Local implementation complete for review. Production edge quotas, actual Auth MFA/recovery behavior and external audit anchoring are operational gates | 14 AdminApiTest cases plus five identity/config tests; all inherited security tests retained; eight pure frontend tests; two real browser flows including positive and negative access; 25 admin SQL assertions on fresh and both reconciled variants | Owner review of M7.1 before M7.2; approved schema inventory/bootstrap and staging MFA/edge/privacy validation before exposure |
| **M7.2 User and Talent Management** | Reusable self-account/talent APIs, Phase 4 verification/evidence/reviewer rosters and verified subscription facts exist. RBAC already defines users.read/suspend/verify | No admin user directory, multidimensional filters/keyset search/detail/history, subscription/talent/booking summary, suspicious-activity panel, verification-review pages, suspension/reinstatement/appeal state machine or backend-wide suspension enforcement | Existing verification APIs keep reviewer RLS and opt-in MFA fencing, but this is not the M7.2 dashboard. No M7.2 suspension/directory tests exist. No suspension feature is enabled or claimed secure | M7.1 review; account restriction policy/reason codes/appeal rules; evidence/privacy permissions; reviewed forward-only read models, indexes, RLS and enforcement contracts |
| **M7.3 Marketplace Operations** | Existing participant booking lifecycle, conversations, reports/moderation/disputes and trust rules; admin permission/MFA adapters retain original reviewer rosters | No admin booking oversight/search/escalation pages, assignment/priority/SLA workflow, operations monitoring or exceptional-transition contract | Existing participant/state-machine/RLS and reviewer-denial tests remain passing. Legacy endpoints are not broad admin access; no arbitrary booking edit or roster grant added | M7.2 approval, explicit operational transitions, escalation/appeal policy and audit/optimistic concurrency design |
| **M7.4 Analytics and Reporting** | Existing consented Phase 6 aggregates and verified subscription/entitlement sources; analytics.read/finance.read permissions | No executive KPI pages, date/comparison views, authorized CSV exports or indexed business reporting refresh pipeline. Booking value is not platform revenue | Existing consent/minimum-cell/roster tests retained. No M7.4 reporting or revenue-accuracy tests exist. No million-user throughput or complete analytics claim | M7.3 approval, KPI formulas/provenance/freshness, consent/retention, verified billing semantics, aggregation/index/query-plan and capacity validation |

## Validation

Java 21.0.12.1, Maven 3.9.11, Node 24, Docker PostgreSQL 17, Chromium on Linux. SQL/JDBC connections are synthetic, restricted and local; outbound providers are mocks. Java fresh/reconciled and real-browser preparation run sequentially because they reset the same named disposable DB. Independent SQL suites use separate disposable containers. No hosted service or Windows/native device was used in this run.

| Check | Result |
| --- | --- |
| New cookie regression before correction | Expected FAIL — raw fixture flag removed production host prefix |
| Integrated Java21 `mvn clean verify`, fresh schema | PASS — 195 tests, zero failures/errors/skips; executable jar and JaCoCo generated |
| Java21 verify, reconciled legacy schema | PASS — 195 tests, zero failures/errors/skips; jar and JaCoCo generated |
| `npm run test:sql` | PASS — existing suites plus admin25 and worker37 assertions |
| `npm run test:reconciliation` | PASS — 516 assertions/catalog gates, both legacy variants; both admin and worker suites execute |
| Admin `npm run test:coverage` | PASS — 8 tests; security.ts 100% lines / 97.06% branches; not whole-dashboard coverage |
| Admin TypeScript, lint, format | PASS |
| Admin production Next build | PASS — optimized App Router build; no deployment |
| Admin `npm run test:integration` | PASS — 2 Chromium flows; real Next/Spring/local signed Auth fixtures/PostgreSQL; Axe and responsive checks retained |
| Root/mobile TypeScript and lint | PASS |
| Root/mobile unit and component tests | PASS — 76 and 17 |
| `git diff --check` | PASS |

The first SQL invocation raced a stopped local container's startup and returned `FATAL: the database system is starting up`; the subsequent actual suite passed after readiness. No production code was changed to hide it. Maven's existing fixture stdout/Byte Buddy warnings are recorded separately from actual Surefire results. The new browser probe initially expected an authorization response without the required session header; Spring correctly returned a validation 400. The final test separately asserts that 400 and uses a syntactically valid forged cookie to exercise the actual server session/RBAC denial (403). No authorization expectation was relaxed. A formatting check also found the newly edited test needed the project-pinned formatter; it was formatted and the check passed. Browser screenshots were isolated via CASTLY_ADMIN_SCREENSHOT_DIR; all six PNGs were fully decoded with Pillow and have nonzero dimensions (overview widths 360/390/430/1440, plus administrator and audit views at 1440). Historical snapshots are unchanged.

Historical migration files remain unchanged, including:

- `20261119120000_admin_rbac_audit.sql`: SHA-256 `6df834384e9c5d198960335601355207e0004d419f279eaecefd018a0b7bc45a`.
- `20261112120000_phase6_engagement.sql`: SHA-256 `e96f83ea35c12200a194c384feebf2e550762b64078a3f3b7bd2d7875d74595a`.

## Remaining security and scale gates

M7.1 remains a code-review checkpoint, not a production-ready claim. The existing migration uses restricted castly_api and invoker functions, with no service-role, BYPASSRLS, authenticated membership or auth-schema grants. All business authorization stays in Spring and RLS; Next contains no database client or parallel business backend.

Still unverified: actual hosted source/ledger equivalence; real Supabase TOTP enrollment, AMR/session semantics, recovery, refresh and revocation; per-account/IP provider/edge quotas across replicas; trusted proxy/IP provenance; audit-key custody, retention and independently anchored immutable export; full JVM/container/secret-history/DAST review; HTTPS deployment cookie behavior; measured query latency, load, DR and million-user capacity. The inherited mobile dependency report recorded high/moderate findings; no new audit or risk acceptance is inferred here. Phase 6 provider, worker identity/pooler, native push, observability and activation gates remain separate and disabled.

M7.1 already uses bounded keyset pagination, indexed primary-key/session/token lookups and per-actor transactional quotas. Those design choices do not establish capacity at millions of users. M7.2 must avoid OFFSET/full-table ILIKE/count scans, unrestricted account/document reads, table-wide UPDATE and service-role dashboards.

## Next implementation plan after review

1. Approve this local integration and corrected M7.1 boundary. Separately approve any push or merge into the original Phase 7/milestone branch. No production action is part of that approval.
2. For M7.2, define a minimized user/talent operational projection with indexed keyset directory filters (role, restriction state, verification, created range) and bounded search; reuse verified entitlement facts and booking metadata under dedicated read permissions. Do not expose identity documents or payment secrets in support summaries. Plan indexes and EXPLAIN against representative isolated datasets before capacity claims.
3. Reuse Phase 4 verification requests/decisions/evidence policy and consent, retaining reviewer membership plus users.verify/recent MFA and immutable reasons/history. Support agents receive no signed document URLs. Test stale decisions, roster/permission combinations and storage isolation.
4. Design a separate forward-only restriction/appeal ledger with policy-approved temporary/permanent states, effective/expiry dates, reason codes, versioned transitions and audited reinstatement/review. Enforce restrictions in backend business transactions and RLS, addressing concurrency with booking/message/profile operations while preserving records, existing financial obligations and access to approved appeal/privacy/support routes. Never implement restrictions only in the dashboard.
5. Build the four M7.2 pages using the current design tokens/components, typed Spring contracts, explicit loading/empty/denial/stale/conflict states and accessible confirmations. Add positive/negative HTTP, SQL, concurrency and real BFF/browser tests. Stop for M7.2 review before M7.3. M7.3 and M7.4 remain separate milestones; Phase 8 performance/security work is not implicitly complete.

## PowerShell reproduction

Use the dedicated integration checkout with Node24, Java21, Maven and Docker Desktop Linux containers. Do not set hosted test URLs or run concurrent fixture-resetting commands.

```powershell
git status --short
git log -3 --oneline
git diff --check
npm ci
npm run typecheck
npm run lint
npm test
npm run test:components
Push-Location backend
try {
    mvn clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Fresh Java regression failed' }
    $env:CASTLY_TEST_SCHEMA_PATH = 'reconciliation'
    mvn clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Reconciled Java regression failed' }
} finally {
    Remove-Item Env:CASTLY_TEST_SCHEMA_PATH -ErrorAction SilentlyContinue
    Pop-Location
}
# Start existing isolated SQL containers if stopped, then wait for pg_isready.
docker start castly-phase2-policy-db castly-issue1-reconciliation-db
docker exec castly-phase2-policy-db pg_isready -U postgres
docker exec castly-issue1-reconciliation-db pg_isready -U postgres
npm run test:sql
if ($LASTEXITCODE -ne 0) { throw 'SQL regression failed' }
npm run test:reconciliation
if ($LASTEXITCODE -ne 0) { throw 'Reconciliation failed' }
Push-Location admin-dashboard
try {
    npm ci
    npm run typecheck
    npm run lint
    npm run format
    npm run test:coverage
    npm run build
    npx playwright install chromium
    npm run test:integration
    if ($LASTEXITCODE -ne 0) { throw 'Admin browser integration failed' }
} finally { Pop-Location }
```

These are local validation commands, not migration/deployment commands. A separately approved owner/bootstrap procedure remains in the existing M7.1 documentation; it was not run here. No merge, push or production activation is authorized by this report.
