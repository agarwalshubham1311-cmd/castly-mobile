# Render release preparation — validation report

Branch: `feat/milestone5-booking-messaging-notifications`; baseline `2a8951be007a2343073b56daa697da2d4115f27e`. Validation performed locally on 2026-11-07 UTC. **Ready for owner review of the deployment checkpoint; not certified as a hosted production deployment.** No deployment, hosted SQL, notifications enablement, EAS build, UI redesign or merge was performed.

## Changes ready for review

- Java 21 multi-stage Docker image with non-root UID/GID 10001, small JRE runtime, exec-form Java entrypoint, memory percentage limit and allowlisted context. Only main sources/resources and the POM enter the build; credentials, tests and migrations do not.
- Existing `PORT` support is retained, with explicit all-interface binding, graceful shutdown and minimal database-dependent public Actuator health. Automatic SQL initialization is explicitly disabled.
- API-only Render Blueprint and separate optional Static Site Blueprint; future auto-deployment off, no migration/pre-deploy commands or committed secrets. Initial Blueprint/service creation itself remains an approval checkpoint.
- Dedicated-role read-only database connection checker; role-safety checks shared with startup and fail closed for missing flags. The checker uses the real JDBC driver and verifies the private invoker helper without changing identity or writing data.
- Public-only Expo release template and fail-closed release guard. Spring business mode is explicit; existing development fallback remains available only under its existing conditions. Real owned package/project values are supplied externally to dynamic Expo config.
- EAS internal preview APK and store production AAB profiles, remote version increment and separate public build environments. No signing credentials, project identity or API URL invented.
- Windows fixture paths now use the platform temporary directory for the existing identity-catalog tests. No business transitions, JWT verifier, TLS enforcement, transaction-local binding, hosted grants or mobile screen components changed.

## Checks

| Gate | Result | Evidence / practical limit |
| --- | --- | --- |
| Java 21 `mvn clean verify`, fresh history | PASS | 90 tests, 0 failures/errors/skips |
| Java 21 `mvn clean verify`, reconciliation | PASS | 90 tests, 0 failures/errors/skips; notification-absent baseline also exercised by existing fixture |
| `npm run test:sql` | PASS | 53 policy/security assertions, disposable PostgreSQL; no hosted URL |
| `npm run test:reconciliation` | PASS | 286 assertions/catalog gates, both legacy reconciliation scenarios |
| `npm run typecheck` / `npm run lint` | PASS | No errors |
| `npm test` / `npm run test:components` | PASS | 53 unit tests / 4 component tests |
| `npm run test:backend` | PASS | Real Java HTTP/JWT/JWKS, PostgreSQL and Chromium; acceptance, one conversation, two-way messages, recipient notifications, outsider denial, offline recovery and no PostgREST business writes |
| `npm run test:mobile-api` | PASS | Deterministic browser HTTP fixture: pagination, refresh/403, onboarding, account, metadata and no direct business DB operations |
| `npm run test:legacy-backend` | PASS | Explicit development fallback, local intercepted Supabase contracts; no hosted project contacted |
| `npm run test:ui` / `test:screenshots` | PASS | Ten screens; all 30 fresh PNGs decode with exact 360×800, 390×844, 430×932 dimensions and nonblank content |
| `test:browser`, `test:portraits`, `test:accessibility` | PASS | Navigation/save/filter/image regressions; 48px controls, responsive sticky profile controls and 150% text simulation |
| `npm run export:web` | PASS | Release guard enabled, synthetic HTTPS origins/public key; successful export does not imply those fixture origins serve an API |
| EAS dynamic public config | PASS | Synthetic UUID/package correctly evaluated in EAS_BUILD context; actual owner values still required |
| Blueprint syntax/local invariants | PASS | Parsed YAML, disabled auto-deploy, separated public/server env, no migration command; provider schema validation still pending |
| Default online Docker build | BLOCKED | Build-container DNS: `Unknown host repo.maven.apache.org: Temporary failure in name resolution`; no TLS/network-security settings changed |
| Same Dockerfile using cache-seeded official Maven builder | PASS | Offline public dependency cache, official Java 21 JRE runtime. Default build arguments remain official images. This does not substitute for Render's clean network build. |
| Non-root runtime, injected PORT, health / protected API | PASS | UID/GID 10001; test PORT 18081; health 200 with only `{"status":"UP"}`; private account 401 |
| JDBC full TLS / restricted read-only probe | PASS | Disposable PostgreSQL with generated local CA; role/helper/read-only/TLS true; notifications flag false |
| Wrong hostname / untrusted CA | PASS (expected failures) | Both reject connection with SQLSTATE 08006, exit 1; verifier never relaxed |
| Actual hosted Supabase pooler / project JWT compatibility | NOT RUN | Approved host/ref/credentials/issuer absent; do not infer correctness of a historical hostname or change signing mode |
| Actual Render resource creation / remote Blueprint validation | NOT RUN | Deployment approval withheld; provider schema download returned HTTP 403 |
| Hosted SQL / live recipient Realtime transport | NOT RUN | Requires exact migration approval and real two-account WebSocket/reconnect checks |
| Native Android/iOS and EAS APK/AAB/signing | NOT RUN | No device/emulator or cloud build used; profiles/configuration only |

Raw local evidence and a machine-readable summary are in [artifacts/deployment/render](../../artifacts/deployment/render/). The regenerated screenshot manifest records the temporary verification run; original tracked Phase 1 screenshots were preserved to avoid deployment-only screenshot churn. The generated image copy remains local to this execution, not a claimed new hosted artifact.

Expected test diagnostics include intentional 42501/403 and DNS failures in negative tests. Java emits Byte Buddy dynamic-agent warnings and Surefire's fixture-stdout “Corrupted channel” warning; all test reports still record zero errors/skips. Node reports the existing typeless-package warning and React emits test-renderer deprecation notices. They were not silenced through unrelated package or runtime changes. The first container smoke failed because the generated test CA file was unreadable by UID 10001; making only that public test CA readable resolved it. Render's approved public CA must likewise be readable by the non-root runtime.

## Exact hosted migration checkpoint

The unchanged pending artifact is **`supabase/migrations/20261107170000_invoker_notifications.sql`**.

SHA-256: `787120cbd2ff0bae50bc000c00cf289b73753199df6e52921907be693d4d0d95`.

The existing private invoker identity fallback is a prerequisite already reported live. Initial Render API deployment uses **`CASTLY_NOTIFICATIONS_ENABLED=false`**. Baseline health, restricted connection, JWT and booking/message smoke checks precede a separate approval to apply only this exact notification migration. Hosted recipient/RLS/trigger/publication checks and real Realtime tests precede a further reviewed flag change. Older proposals and unrelated migrations are not applied. See the [exact deployment order and PowerShell/dashboard instructions](RENDER_RELEASE.md) and the existing [invoker notification security/deployment report](../milestone5/invoker-notifications-deployment.md).

## Short release checklist

- [ ] Review final commit and choose actual Render names/region/plan, pooler endpoint and trusted CA, unchanged JWT mode, exact web origins, owned EAS project/package.
- [ ] Approve the initial API deployment; clean Render Docker build must pass. Keep notifications **false**; no migration command.
- [ ] Verify read-only connection, minimal health, private 401, valid JWT and existing client/talent booking/conversation/message flow.
- [ ] Approve optional separate web deployment and EAS preview build; verify actual device/network/session behavior. Do not submit production AAB automatically.
- [ ] Separately approve only the exact notification migration; verify security/publication and real recipient Realtime before enabling notifications.

If migration-trigger behavior is faulty, disabling the API flag alone does not remove triggers or prevent them rolling back booking/message writes. Any database recovery must be targeted and explicitly reviewed.
