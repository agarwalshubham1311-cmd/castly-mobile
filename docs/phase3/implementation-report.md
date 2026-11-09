# Phase 3 implementation report

Implemented locally on 2026-11-09 for the 2026-11-13 release-readiness target. **The code is ready for review; live monetization remains blocked on approved Google/native/infrastructure setup and real purchase testing.** No hosted SQL, Render deployment, EAS build, production secret modification or real payment activation occurred.

## Delivered behavior

- **P3-M1:** forward-only configurable catalog and grant/order/event/audit model; one-time legacy entitlement reconciliation; effective active/expired/revoked/overlapping memberships; public plans, private status/history and compatible existing entitlement response. Server portfolio enforcement supports approved finite limits while NULL defaults preserve existing unlimited Free behavior. Published discovery receives an expiry-checked premium eligibility boolean; future premium feature flags are server-configured. Existing booking/messaging access remains Free, with no commission.
- **P3-M2:** canonical public promotional URL submission, own status, trusted reviewer queue/UI, approve/reject reasons, immutable review decisions and one calendar-year Creator Pro grant. Unique creator/post claims, row/account locking, audit/projection transactions, self-review denial and rate limits protect the workflow. Social video verification is manual; content/engagement is not fabricated. Rejected claims are retained; automatic appeals/resubmissions are not implemented pending policy.
- **P3-M3:** actual Google OAuth/service-account HTTPS client, subscriptionsv2 verification and acknowledgement, strict package/product/base-plan/account binding, unique token/order handling, linked-token retirement, pending/grace/hold/cancelled/expired/revoked lifecycle handling, authenticated Google Pub/Sub RTDN and replay-safe reconciliation. Revocation preserves overlapping promotion grants. Native adapter uses **expo-iap 6.4.3**, real store product/offer queries, readable store-supplied pricing phases, purchase callbacks, restore, pending states, safe error/cancellation handling and account-switch/duplicate protection. Backend processing and new-checkout gates are independent, so checkout can pause while Restore/RTDN continue.
- **P3-M4:** session-safe Lavender Pearl membership/founding/admin screens, protected Expo Router routes and Profile entry point, SQL/service/security/provider/adapter/component/browser coverage, compatibility review, migration/security/setup/release documentation and final evidence artifacts.

The initial react-native-iap/Nitro candidate was rejected after its published source explicitly excluded Expo Dev Client support. It was removed completely from runtime imports/dependencies/lockfile. The final Expo Module's actual declarations and config plugin were inspected and resolved locally; native Expo 58 compatibility is still unverified without an approved build.

## API and configuration

Implemented under `/api/v1`: GET `/subscriptions/plans`, `/subscriptions/me`, `/subscriptions/history`; POST `/subscriptions/google-play/verify`, `/restore`; GET `/subscriptions/google-play/configuration`; authenticated Google POST `/subscriptions/google-play/rtdn`; POST `/subscriptions/promotions/claims`; GET `/subscriptions/promotions/me`; GET `/admin/subscriptions/promotions`; POST `/admin/subscriptions/promotions/{id}/approve` and `/reject`.

Responses use snake_case; paging and payloads are bounded. Provider tokens, credentials and internal audit payloads are excluded from client responses. Admin identity comes from an owner-provisioned private database allowlist, never client JWT metadata. Existing JWT/TLS verification and Java 21/Spring Boot 3.5.7 remain intact.

All rollout gates default false: `CASTLY_SUBSCRIPTIONS_ENABLED`, `CASTLY_GOOGLE_PLAY_ENABLED`, `CASTLY_GOOGLE_PLAY_CHECKOUT_ENABLED`, and native `EXPO_PUBLIC_GOOGLE_PLAY_BILLING_ENABLED`. `CASTLY_NOTIFICATIONS_ENABLED=false` remains unchanged for deployment. Exact variables and stable HMAC-key requirements are documented in backend/mobile `.env.example` and [Google Play setup](google-play-setup.md). No prices/product IDs are invented. Expo Go/web retain the screens with native purchase/restore unavailable.

## Migration artifacts

Four new files, in order: `20261109090000_subscription_catalog.sql`, `20261109100000_founding_creator_offer.sql`, `20261109110000_google_play_reconciliation.sql`, `20261109120000_subscription_discovery_projection.sql`. [Migration review](migration-review.md) includes exact SHA-256 hashes, dependencies, hosted-inventory/backup preflight, least-privilege review and post-apply checks. No historical migration was modified and no pending notification migration is required by Phase 3. Existing notification migrations were exercised only in disposable inherited regression fixtures.

The old entitlement table becomes a compatibility projection after activation. Owner-managed grants must move to the ledger/projection workflow; do not independently mutate the old table. Unknown legacy entitlement names are preserved and require hosted inventory before mapping to Creator Pro.

## Executed validation

Evidence: [artifacts/phase3](../../artifacts/phase3/README.md), [machine-readable results](../../artifacts/phase3/validation.json), [browser membership screenshot](../../artifacts/phase3/creator-pro-390x844.png).

| Check | Actual result |
| --- | --- |
| Java clean verify, fresh repository schema | 111 tests; 0 failures/errors/skips |
| Java test, reconciled schema | 111 tests; 0 failures/errors/skips |
| Root TypeScript/domain/transport tests | 71 passed |
| React component tests | 8 passed |
| SQL migration/RLS assertions | 66 passed |
| Existing reconciliation SQL assertions | 286 passed |
| TypeScript typecheck / ESLint / client-env guard | Passed |
| Expo web export / local expo-iap plugin configuration resolution | Passed; no native build |
| Real Spring/JWT/PostgreSQL browser suite | Passed bookings/messages plus founding submission, trusted admin approval, 12-month membership and discovery eligibility |
| Deterministic mobile HTTP browser fixture | Passed; no direct Supabase business writes |
| Original browser / Lavender UI / accessibility / portrait / screenshot regressions | Passed |

Exact commands were run from the repository root:

```sh
JAVA_HOME=/workspace/tools/jdk21 /workspace/tools/apache-maven-3.9.11/bin/mvn -f backend/pom.xml -o -Dmaven.repo.local=/workspace/.m2/repository clean verify
CASTLY_TEST_SCHEMA_PATH=reconciliation JAVA_HOME=/workspace/tools/jdk21 /workspace/tools/apache-maven-3.9.11/bin/mvn -f backend/pom.xml -o -Dmaven.repo.local=/workspace/.m2/repository test
npm run typecheck
npm run lint
npm test
npm run test:components
npm run test:sql
npm run test:reconciliation
npm run check:client-env
# Local-only fixture web export; credentials below are synthetic public fixture values.
EXPO_NO_TELEMETRY=1 EXPO_OFFLINE=1 EXPO_NO_DOTENV=1 __UNSAFE_EXPO_HOME_DIRECTORY=/tmp/castly-phase3-expo EXPO_PUBLIC_BACKEND_MODE=supabase EXPO_PUBLIC_BUSINESS_API_MODE=spring EXPO_PUBLIC_SUPABASE_URL=http://127.0.0.1:8098 EXPO_PUBLIC_SUPABASE_ANON_KEY=sb_publishable_local_fixture EXPO_PUBLIC_API_URL=http://127.0.0.1:8080 npm run export:web -- --output-dir /tmp/castly-phase3-web-final
# Fixture preview on 8086; independent mock preview on 8082 for original UI tests.
CASTLY_JAVA=/workspace/tools/jdk21/bin/java CASTLY_SCREENSHOT_DIR=/tmp/castly-phase3-spring-shots npm run test:backend
npm run test:mobile-api
npm run test:browser
npm run test:ui
npm run test:accessibility
npm run test:portraits
npm run test:screenshots
# Resolves configuration/plugin only; it does not create/build native projects.
EXPO_NO_TELEMETRY=1 EXPO_OFFLINE=1 EXPO_NO_DOTENV=1 __UNSAFE_EXPO_HOME_DIRECTORY=/tmp/castly-phase3-expo EXPO_PUBLIC_GOOGLE_PLAY_BILLING_ENABLED=true npx expo config --type prebuild --json
```

Tests include concurrent paid verification, duplicate/linked tokens, cross-account denial, expiry/overlap/portfolio limits, concurrent approvals, forced audit rollback, Google OAuth HTTP contracts, actual bearer-filter signature/issuer/audience/email rejection, pending/restore/cancellation/native teardown, and stale previous-account UI response rejection. Java/backend fixtures were serialized to avoid shared-database resets. Initial local test setup failures (a reused fixture UUID and a stopped disposable SQL container) were corrected; the final checks above passed.

## Git and review delivery

Dedicated worktree: `/workspace/castly-phase3`; branch: `feat/phase3-subscriptions-monetization`. The latest remote development branch `02361e7f23e7025a1ca93ee7f6f384b6f5b4ce29` was fetched and reconciled. Its original Phase 3 design/helper contracts and five tests were retained, as were `com.castly.mobile` and preview APK intent. Original local Render preparation d065bf5 was preserved as d60aab4. No base/main/parallel APK branch was overwritten.

Implementation commits: d60aab4 (preserved Render preparation), f15325d (M1), f172907 (M2), 1a143ea (M3), 62a3079 (M4/security/QA), 56bd066 (final Expo-native adapter and independent checkout/restore gates). Final documentation/evidence commits follow these. The feature branch was successfully published. Creating a draft PR failed with GitHub API `Post "https://api.github.com/graphql": Forbidden`; GitHub CLI authentication status also reported invalid GH_TOKEN. No credential settings were changed. There is no PR URL from this attempt; [review/create the PR](https://github.com/agarwalshubham1311-cmd/castly-mobile/compare/feat%2Fmilestone5-booking-messaging-notifications...feat%2Fphase3-subscriptions-monetization?expand=1) with the [prepared PR body](pr-description.md).

## Release blockers and limits

1. Official Google/Expo documentation requests were blocked by the network proxy (403). Published npm source was inspected; current official compatibility/policy must still be verified. See [compatibility evidence](google-play-compatibility.md).
2. No Play Console products/prices, backend service-account permissions, stable account-association secret, authenticated Pub/Sub infrastructure or reviewer identities were provisioned. Purchase controls remain disabled.
3. No Android custom/Play build or physical-device billing test was run; no live Google purchase, acknowledgement, renewal, refund or replacement was validated. Adapter replacement support exists, but v1 has no multi-tier upgrade selector until an approved expanded catalog exists.
4. Hosted schema equivalence, hosted Realtime and deployed JWT/TLS behavior were not tested. Explicit approval is still required before hosted SQL, Render deployment, secrets, EAS/native cloud builds or live billing activation.
5. Production retention/account-deletion policy, moderation appeals, finite portfolio limit rollout and Pub/Sub retry/dead-letter monitoring require operational decisions. No stored-token polling worker exists; missed notifications recover through Pub/Sub retries/monitoring or Restore Purchases.

Use the [security review](security-review.md), [Google Play setup](google-play-setup.md), and [release checklist](release-checklist.md) before any activation. Local deterministic verification is not a claim that monetization is live.
