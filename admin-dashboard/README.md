# Castly operations — M7.1 review build

A separate Next.js application, backed exclusively by the existing Spring business API and Supabase Auth. No database connection, service-role key, provider activation or fake production KPIs. Both new backend/UI flags default false. M7.2 and later screens are intentionally not implemented before approval.

Prerequisites: Node 24, Java 21, Maven, Docker and Playwright Chromium. Run from the repository's new Phase 7 branch. Full PowerShell commands, migration review and test results: `../docs/phase7-milestones.md`. Fixed loopback integration fixtures use disposable PostgreSQL, signed JWTs and mocked Auth/TOTP; they never use hosted credentials.

```powershell
npm ci
npx playwright install chromium
npm run typecheck
npm run lint
npm run format
npm run test:coverage
npm run build
# From ../backend, first run mvn clean verify. Then return here:
npm run test:integration
```

`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` is an optional explicit local-browser override. The runner uses os.tmpdir and fixed local ports 3017/19180/19198, resets only `castly_spring_test` in the named local Docker container, and terminates its own child processes. Do not run it concurrently with the Java DB suites or inherited backend-browser tests. No Windows execution is claimed; new scripts are designed without shell-only temp/browser assumptions.

After separate staging approval, configure `.env.example` as server-only environment, keep approved exact HTTPS origins, enable the reviewed backend flag/schema and provision the initial administrator through the separately approved owner procedure. No self-enrollment or super-admin promotion endpoint exists. Admins must already have an approved verified Supabase TOTP factor; factor enrollment/recovery is an owner-controlled identity operation, not automated here.

Production cookies are Secure, HttpOnly, SameSite Strict and __Host-prefixed. Local fixture mode is accepted only when all three service origins are explicit loopback HTTP addresses; never set CASTLY_ADMIN_LOCAL_TEST outside the isolated runner. The dashboard does not trust browser role/MFA metadata. A 1h backend session binds the verified JWT subject/Auth session ID and principal security version. Fresh MFA ≤5m is required for invitations/activation/role changes; reauthenticate using the header link. Another administrator's sessions are invalidated by an authorized principal version change. Super admins remain owner-managed.

Known rollout gates: real Supabase MFA/refresh/recovery testing, provider/edge auth quotas, audit peer-IP provenance/retention and independent immutable anchoring, hosted migration ledger/dependency reconciliation and owner bootstrap approval. UI periodically rechecks access; every data/action request checks backend authorization immediately. Existing Phase 3 promotion review keeps its legacy reviewer roster; enabling the new admin flag additionally requires the new MFA/session gate. New roles alone cannot grant reviewer access. The Next proxy exposes no legacy review routes.
