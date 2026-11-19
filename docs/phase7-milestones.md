# Milestone checkpoints

M7.1: implemented and locally validated; pending owner review. See architecture/RBAC/contracts and final test report appended here. Scope is admin authentication, session/MFA/revocation, invitations/roles, audit, dashboard shell and tests. M7.2–M7.4 are blocked on owner review/approval of M7.1. No suspension/verification/booking-moderation writes or business analytics claims are introduced.

Phase 8 preparation is documented now; its execution is deferred to the ordered milestones after Phase 7 approvals. No deployment, privileged bootstrap, provider activation or hosted migration is authorized.

## M7.1 implementation summary

Implemented a separate Next.js App Router/Tailwind dashboard and same-origin cookie BFF, existing Supabase password/verified TOTP integration, fixed-path Spring proxy, backend role/permission authority, short session/expiry/revocation/version binding, recent-MFA sensitive actions, single-use targeted invitations, owner-only super-admin bootstrap artifact, DB atomic quotas/session-fanout detection, private RLS authorization projection and transactional append-only audit. No privileged operations moved into Next. Six roles have granular fixed permissions. New flags `CASTLY_ADMIN_ENABLED` / `CASTLY_ADMIN_UI_ENABLED` default false; existing flags/providers are unchanged. `CASTLY_ADMIN_AUDIT_KEY` is backend-only and requires ≥32 characters when enabled. Production cookies are __Host-prefixed, Secure, HttpOnly and Strict; local fixture exception requires all service origins to be loopback.

Architecture and boundaries: phase7-architecture.md / phase7-admin-rbac.md. REST endpoints: phase7-api-contracts.md. New UI pages: login, MFA, invitation activation, overview/own sessions, administrators and audit. No M7.2 suspension/verification/user directory, M7.3 moderation/booking operations or M7.4 marketplace/revenue analytics is implemented. Existing Phase 3 promotion-review endpoints retain their reviewer roster and baseline behavior while the new flag is off. When enabled they additionally require the new MFA/session fence (subscriptions.read for queue, admins.manage + recent MFA for decisions). Both permissions and the legacy roster must authorize; no membership is granted. Legacy successful decisions remain in their existing subscription audit ledger, not a fabricated duplicate timeline.

Migration `supabase/migrations/20261119120000_admin_rbac_audit.sql` SHA256 `6df834384e9c5d198960335601355207e0004d419f279eaecefd018a0b7bc45a` adds only private admin tables/policies/helpers/triggers/grants. It does not grant auth schema access, alter business RLS or historical migrations, use SECURITY DEFINER or elevate DB membership. Seven new tables have RLS. The actor-only authorization projection avoids recursive policies while allowing permission-controlled directory updates. Application audit rows cannot be changed/deleted/truncated; source writes require their audit row in the same transaction. DB owners remain outside these application-level tamper controls. Initial administrator creation is never automatic.

Changed-file groups: `admin-dashboard/` (independent package/lock/config, server routes, shell/forms/dialogs/tables, tests); `backend/src/main/java/com/castly/admin/` and new admin integration/identity tests; minimal API security/filter and validation integration, settings and JaCoCo; isolated fixture/reconciliation/SQL runners and security SQL; `.github/workflows/admin-quality.yml`; root TS/lint exclusions for the independent Next app; docs and local-only screenshot/security artifacts. Existing mobile UI, booking/message/subscription implementation and Render/EAS configuration remain unchanged.

## Exact local PowerShell sequence

Use Node24, Java21, Maven, Docker Desktop running Linux containers. Ensure `java -version` reports 21 and `mvn -version` uses that JDK; set JAVA_HOME to your actual installed JDK21 directory if needed. Never set hosted database URLs in test shells.

```powershell
git switch feat/phase7-admin-phase8-readiness
npm ci
npm run typecheck
npm run lint
npm test
npm run test:components
Push-Location backend
mvn clean verify
$env:CASTLY_TEST_SCHEMA_PATH = 'reconciliation'
mvn clean verify
Remove-Item Env:CASTLY_TEST_SCHEMA_PATH
Pop-Location
# Reuse the local fixture container; no hosted DB target:
$env:CASTLY_TEST_DB_CONTAINER = 'castly-spring-test-db'
npm run test:sql
Remove-Item Env:CASTLY_TEST_DB_CONTAINER
npm run test:reconciliation
Push-Location admin-dashboard
npm ci
npx playwright install chromium
npm run typecheck
npm run lint
npm run format
npm run test:coverage
npm run build
npm run test:integration
npm audit --json | Set-Content -Encoding utf8 dependency-audit.json
Pop-Location
npm audit --json | Set-Content -Encoding utf8 mobile-dependency-audit.json
```

Run Java fresh/reconciled and browser integration sequentially: they deliberately share only a disposable named DB. New browser tests use Playwright-managed Chromium or an explicitly chosen `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`; no fixed Unix binary is required. JaCoCo reports are `backend/target/site/jacoco/index.html` and `jacoco.xml`. Node coverage uses its built-in test runner. New CI is code-only and does not configure branch protections or deploy.

## Reviewed migration and bootstrap order — DO NOT RUN before approval

1. Resolve remote baseline/integration lineage; inventory actual hosted schema/ledger/owners/grants and confirm the private identity helper/restricted role. Do not merge Phase4–6 automatically. Review the migration SQL/hash and rehearsal results.
2. Obtain separately approved backup/change window and verify restore procedures. Keep both admin flags off. Select a reviewed owner PostgreSQL service definition with TLS verify-full and secrets outside Git; never install its credentials in Spring/Next.
3. Apply only the reviewed forward SQL, never old notification proposals or unrestricted db push. Verify tables, policies, grants, helper invoker modes and unchanged business workflows before staging flag approval.
4. Confirm a real pre-existing account/verified TOTP enrollment and two-person super-admin approval. Only then execute the separate bootstrap artifact as the actual new table owner. No operator account is provisioned by these code changes.
5. Test staging JWT/MFA/refresh/recovery/invalidations/audit, independent edge quotas and privacy retention/anchoring; approve UI/backend flags independently. Production exposure is a separate approval.

PowerShell review commands (safe/read-only locally):

```powershell
Get-FileHash supabase/migrations/20261119120000_admin_rbac_audit.sql -Algorithm SHA256
Get-Content supabase/migrations/20261119120000_admin_rbac_audit.sql
Get-Content supabase/operations/admin-bootstrap-reviewed.sql
```

Only after explicit migration/owner-bootstrap approvals and a separately configured protected `castly-reviewed-owner` pg_service definition:

```powershell
psql 'service=castly-reviewed-owner' -X -v ON_ERROR_STOP=1 -f supabase/migrations/20261119120000_admin_rbac_audit.sql
# A separate action/approval; replace with the reviewed existing account UUID:
psql 'service=castly-reviewed-owner' -X -v ON_ERROR_STOP=1 -v bootstrap_approved=M7.1-owner-approved -v admin_user=REVIEWED_EXISTING_ACCOUNT_UUID -f supabase/operations/admin-bootstrap-reviewed.sql
```

The bootstrap rejects application roles, missing approval and duplicate principals. Its synthetic test counterpart is only executable against the named local fixture DB. Emergency revocation of a SUPER_ADMIN requires separately approved owner SQL (update state under the bootstrap guard, which increments version and records audit); no broad application grant is a workaround. Roll back application exposure by disabling flags; preserve forward schema/audit and issue a reviewed follow-up migration rather than destructive rollback.

## Local validation and review checkpoint

Full evidence and precise limitations: `phase7/test-report.md`; dependency findings and unresolved gates: `phase7/security-findings.md`; SQL hash/order: `phase7/migration-review.md`; screenshot manifest and machine-readable npm/coverage summaries: `artifacts/phase7/`. Java21 fresh/reconciled each pass 129 tests; SQL/RLS25 and reconciliation336 pass; admin pure-security6 plus real browser/Axe checks pass; existing mobile unit71/components8, TypeScript/lint, web export, browser/portraits/ten-screen UI/30 decoded screenshots/accessibility regressions pass. Incidental generated baseline snapshots were restored. Root/mobile dependency security gate remains FAIL (3 high/14 moderate inherited findings); no risk acceptance is implied. Live MFA, hosted/native/capacity and broader Phase8 checks remain unverified.

M7.1 is prepared for code/security review, not production launch. Next action: review this branch, migration, auth/session/legacy-review fence, provider/edge quotas and retained scan report. Explicitly approve M7.1 before M7.2 implementation. Separately approve any hosted migration, real administrator bootstrap or staging/production exposure. No such action occurred during this task.
