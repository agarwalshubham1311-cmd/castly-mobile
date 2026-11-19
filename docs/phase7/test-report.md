# M7.1 executed validation — 2026-11-19

Corrected authoritative base: `3c12b515f5ba2e6ef67d0089d0fd49ea6c99a175` from the requested milestone5 branch, including Phase4–6. Initial stale b86deb4 remote-tracking inspection was corrected before final integrated validation. Isolated branch: `feat/phase7-admin-phase8-readiness`. Java21.0.12.1 / Maven3.9.11 / Node24.19.0 / disposable Docker PostgreSQL17 / Playwright Chromium on Linux. No hosted database, real administrator, external Auth/provider, production service, EAS or store was used.

| Check | Actual result |
| --- | --- |
| Java21 `mvn clean verify`, fresh schema | PASS — 179 tests, zero failures/errors/skips |
| Same verify, `CASTLY_TEST_SCHEMA_PATH=reconciliation` | PASS — 179 tests, zero failures/errors/skips |
| `npm run test:sql` | PASS — inherited checks plus 25 M7.1 SQL/RLS assertions |
| `npm run test:reconciliation` | PASS — 442 assertions/catalog gates on both synthetic legacy variants, including forward admin schema |
| Mobile `npm ci`, typecheck, lint | PASS — 803 packages, no source UI redesign |
| Mobile `npm test` / `test:components` | PASS — 76 / 17 tests |
| Admin npm install/lock, typecheck/typegen, lint, format, build | PASS — independent Next App Router production build, no live configuration |
| Admin `test:coverage` | PASS — 6 pure security contract tests; security.ts 100% lines / 96.15% branches, **not whole-dashboard coverage** |
| Admin `test:integration` | PASS — real Next BFF + signed local ES256/JWKS + Spring + restricted PostgreSQL; login/TOTP/session, targeted invitation/activation, role change, audit, recipient/CSRF/proxy denials and logout |
| Axe WCAG2 A/AA | PASS — no violations on local overview and administrator directory surfaces tested; not a full accessibility certification |
| Admin responsive browser/screenshot | PASS — no page overflow at 360/390/430/1440px; 6 PNGs fully decoded with nonzero dimensions, synthetic local accounts |
| Mock and Spring-mode Expo web exports | PASS — production JS bundle/static output only, not a native build |
| Existing `test:backend` browser + real Spring/PostgreSQL | PASS — booking acceptance/conversation/two-way messages/cancel/reject, private disputes, subscription review/entitlements, Phase4 blocks + Phase5 matching, Phase6 consent/search replay/opaque links; Auth/JWKS local fixtures, no hosted Realtime |
| `test:mobile-api` | PASS — 201-profile deterministic HTTP fixture, auth/expiry/retry/account/portfolio; no direct Supabase business requests |
| Mobile browser/portraits/UI/screenshots/accessibility | PASS — category/search/filter/profile/save/nav, portraits/loading/fallback, ten screens/30 fully decoded mobile PNGs, 48px controls and 150% text simulation; no hosted Realtime or native-device claims |
| Admin npm audit | PASS — zero reported advisories; retained JSON |
| Root/mobile npm audit | FAIL security release gate — 3 high / 14 moderate inherited findings, zero critical reported by npm; no risk acceptance or breaking upgrades |
| CI YAML | Parsed locally; workflow prepared, not claimed to have run on GitHub |
| `git diff --check` | PASS |

The 19 new Java tests comprise 14 multi-case DB/HTTP integration tests and 5 identity/config unit tests. They cover all six roles, non-admin/forged metadata/password-only/future/stale MFA denials, actor/Auth-session binding, own-session isolation, one-hour expiry/revocation, principal-version invalidation, no self/super promotion, targeted/expired/replayed invitations, failed-audit/source rollback, concurrent invitation and role writes, minute quotas/session-fanout, bounded input, direct-role permission/projection/audit forgery and pooled transaction identity cleanup. Existing 160 backend tests retain booking acceptance, conversation/messages, notifications, subscription verification and Google JWT/RTDN boundaries. With the new admin flag enabled, integrated verification/moderation/disputes/trust/review/Phase6 analytics administrative HTTP endpoints additionally require the new permission/MFA/session fence while retaining their reviewer rosters. Legacy promotion review additionally requires new admin MFA/session authorization and its existing reviewer roster; flag-off behavior remains covered by inherited tests. An additional positive source review verifies fresh-MFA approval succeeds only for the pre-existing reviewer and stale-MFA approval fails before the transition.

JaCoCo report is generated at backend/target/site/jacoco (HTML/XML); the retained summary artifacts/phase7/java-coverage-summary.json records executed reconciled counts: overall lines 4141/4718, branches 1318/2052; admin package lines 316/346, branches 102/122. Missing branches/lines remain a review consideration. Percentages alone are not proof of complete security. Browser reports and Java reports are emitted as CI artifacts by the new quality workflow.

Initial local failures were corrected and rerun: invitation UUID string parameter required UUID typing; new gate initially intercepted existing promotion-review endpoints; independent Spring contexts retained pools during the next fixture reset; Next route announcer made an error locator ambiguous. A separate environment-only Maven download failure (DNS then build-JDK trust store) was resolved using the managed network proxy and existing system Java trust store; application TLS, credentials and verification were not changed. Coverage tooling artifacts are now cached and final Java verification ran offline without relaxing TLS.

Limitations/release gates: actual hosted schema/ledger equivalence and deployment state of integrated Phase4–6; real Supabase MFA enrollment/challenge/refresh/recovery/revocation; provider/edge login rate policies; audit privacy retention/key policy/independent anchoring and trustworthy proxy-IP provenance; full JVM/container/secret-history/DAST assessment; actual CI run; native Android/iOS/Windows/device/EAS/billing/store tests and measured capacity/SLO validation. No million-user readiness, production launch or full Phase8 completion is claimed. See security-findings.md and phase8 planning documents. Stop for owner review of M7.1 before M7.2.

Reproduce using PowerShell in phase7-milestones.md. All local Java DB suites and real backend browser preparations must run sequentially. New browser runner uses os.tmpdir and managed Playwright Chromium (explicit executable override used only for this installed Linux browser). All provider/Auth/TOTP accounts are fixtures; no production personal data or credentials are needed.
