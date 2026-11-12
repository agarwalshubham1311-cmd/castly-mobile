# Phase 6 validation — 2026-11-12

Worktree `/workspace/castly-phase6`, branch `feat/phase6-notifications-engagement`, based on milestone5 `1b37a65bc1bbcb1898ec816c81dc8a1da94af9df`. Java 21.0.12.1, Maven 3.9.11, Node 24.19.0 and isolated Docker PostgreSQL 17. All database fixtures are disposable/local; no hosted Supabase, Render, EAS, Play or real delivery provider was used.

| Check | Actual result |
| --- | --- |
| `npm ci --cache /tmp/castly-phase5-npm --prefer-offline --no-audit --no-fund` | PASS, 803 packages |
| `npm run typecheck` | PASS |
| `npm run lint` | PASS |
| `npm test` | PASS, 76 tests |
| `npm run test:components` | PASS, 17 tests |
| Java 21 `mvn clean verify` | PASS, 160 tests, no failures/errors/skips |
| Same verify with `CASTLY_TEST_SCHEMA_PATH=reconciliation` | PASS, 160 tests, no failures/errors/skips |
| `npm run test:sql` | PASS, inherited suites and 24 Phase 6 SQL/security assertions |
| `npm run test:reconciliation` | PASS, 392 assertions/catalog gates across both legacy variants, including Phase 4+5+6 |
| Spring-mode Expo web export | PASS |
| Mock-mode Expo `--platform all` export | PASS, web/Android/iOS JavaScript bundles; not native builds/device tests |
| `npm run test:backend` on fresh Spring export | PASS, real browser + Spring HTTP/JWT/JWKS fixture + PostgreSQL; Phase 6 consent, saved-search replay/pause, own export, disabled-provider disclosure and opaque email-link authorization after guest login; existing Phase 4+5, booking/message/cancellation/subscription flows retained |
| `npm run test:mobile-api` | PASS, deterministic HTTP fixture, 201-profile pagination/identity/expiry/offline handling, no direct Supabase business writes |
| `test:browser`, `test:portraits`, `test:ui`, `test:screenshots`, `test:accessibility` | PASS, mock browser regression suite, ten screens/30 PNGs decoded at expected 360×800, 390×844, 430×932 dimensions, 48px controls and 150% text simulation |
| `git diff --check` | PASS |

Java command: `JAVA_HOME=/workspace/tools/jdk21 /workspace/tools/apache-maven-3.9.11/bin/mvn -o -Dmaven.repo.local=/workspace/.m2/repository clean verify` from `backend/`. Fresh/reconciled Java fixtures and real backend browser preparation ran sequentially to avoid DB-reset races. SQL suites use separate disposable containers. Every API operation uses restricted castly_api with auth schema USAGE denied. Worker tests use a separate restricted fixture login; no admin/service-role app connection. Additional focused cap/cohort cases were run while strengthening midnight dispatch and weekly-retention assertions.

New coverage includes owner-only token metadata, rotation/device cap, other-actor token conflict and expired reclamation, consent required for registration, no recreation of preferences/devices after privacy deletion, untrusted email metadata rejection, source/rollback/idempotency guards, concurrent workers, block acknowledgement serialization, queued suppression after opt-out/block, quiet hours/DST, daily caps on actual dispatch day, retry/dead-letter, invalid-token cleanup, ambiguous dispatch quarantine, delivery receipt versus acceptance, signed email webhook replay/bounce suppression, scoped/expired unsubscribe, no optional-open tracking after unsubscribe, digest ownership/deduplication/blocked-source recheck, re-engagement consent/local-week dedupe, admin aggregation/minimum cell/weekly cohort definitions, analytics opt-out deletion and account-switch/late-response isolation.

Provider sandbox tests inject mocked HTTP responses into the actual Expo/Resend adapters and fake delivery providers into the real worker/database path. They exercise headers, accepted/receipt/error mappings, backoff and state transitions without network sends. Complete-body size/deadline cancellation and a trusted mailbox lookup against a local HTTP server are tested. These are local sandbox fixtures, not tests against a provider-managed sandbox account. The email-link browser fixture is seeded in local PostgreSQL; no real email was generated. Baseline screenshot changes produced by checks are restored, not included as unrelated UI updates.

Limits: actual hosted schema/ledger equivalence, production query latency/locks, SPF/DKIM/DMARC/deliverability, real Expo receipts/APNs/FCM, provider-managed sandbox/live sends, hosted Realtime recovery and Android/iOS physical-device behavior remain unverified. No merge, migration, deployment, environment activation or paid service was performed. All rollout flags/providers default off; release is blocked on the owner checkpoints in `release-checklist.md`.

Local reproduction (with Java 21, Node 24, Maven and Docker available): run `npm ci`, typecheck/lint/test/components, SQL/reconciliation and Maven verify on both fixture paths, then export with the documented local fixture origins and run the browser/mobile checks. Do not substitute hosted database URLs into disposable test commands. Server-only environment names and defaults are in `backend/.env.example`; trusted mailbox enrollment additionally requires the existing server-side `SUPABASE_PUBLISHABLE_KEY`, never a service-role key. Actual sending requires separately approved worker/provider/native configuration.
