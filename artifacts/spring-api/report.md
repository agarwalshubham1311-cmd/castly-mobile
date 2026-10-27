# Castly Spring API — implementation and validation report

Date: 2026-10-27. Branch: `feat/phase2-bookings-messaging`. Starting commit: `64ed176`.

## Outcome

Spring Boot is now the default connected-mode business API for Milestones 1 and 2. Supabase remains responsible for Auth, direct Storage uploads/signing and Realtime transport. Mock mode remains available. The original direct-Supabase M1 implementation is retained only behind an explicit development-only switch, and its regression suite passes. There is no automatic fallback on API failures.

Local implementation and regression gate: **PASS**. Hosted rollout gate: **NOT READY / configuration verification required**. No hosted migration, production deployment, main merge or payment enablement was performed.

## Delivered behavior

- Own-account reads/rename, immutable Client/Talent onboarding, talent create/edit/publish, published discovery with filters, portfolio metadata create/edit/remove, favourites and read-only entitlements.
- Booking creation, participant listing/details and serialized acceptance/rejection/cancellation. Acceptance atomically creates the unique participant-pair conversation. Repeated decisions are idempotent; conflicting terminal transitions fail.
- Participant-only message history, cursor-based older history and sending while an accepted booking exists. Cancellation stops sending and retains authorized history. The mobile booking, conversation and chat routes use typed HTTP services.
- Explicit loading/error/empty/offline handling; drafts survive errors. Confirmed messages are deduplicated, and reconnect refreshes do not silently join history across an unloaded gap. Uncertain network writes are never retried automatically.
- Realtime subscriptions invalidate Spring reads using the current user's session, clean up on unmount/session changes, and recover with foreground/reconnect/manual refresh plus 15-second foreground polling. The UI reports unavailable live updates. No delivery/read receipts are fabricated.

## Security and database reconciliation

JWT validation pins the configured Supabase issuer, requires audience, expiration, UUID subject and authenticated global role, and verifies RS256/ES256 signatures through that project's JWKS. An explicit legacy mode delegates HS256/overlap signature verification to the configured project's Auth user endpoint using only a publishable/anon key; failures deny access. There is no shared signing secret or service-role client credential.

The API connects only as dedicated `castly_api`, with no administrator privileges, ownership, role memberships, entitlement writes or RLS bypass. Startup rejects unsafe identities and URL overrides; remote PostgreSQL requires verified TLS. Every transaction sets and clears both standard Supabase identity-claim formats through transaction-local settings, including anonymous public reads. SQL is parameterized. DTO validation, strict JSON fields/integer conversion, sanitized Problem Details and exact-origin bearer-only CORS are implemented.

The existing booking/messaging migration is preserved. New forward migration [20261027140000_spring_business_api.sql](../../supabase/migrations/20261027140000_spring_business_api.sql) revokes ordinary table **and column** business writes and old privileged onboarding/decision RPC execution, adds API-role RLS/transition enforcement, and configures the participant tables for the existing Realtime publication. Auth and Storage paths remain separate. The new database role starts as NOLOGIN. Nothing applies DDL automatically at API startup.

## Verification results

| Check | Result | Evidence |
|---|---|---|
| `mvn clean verify` in `backend/`, JDK 21 | PASS — 13 tests, 0 failures/errors/skips | [Maven log](logs/maven-java21.log) |
| Java target | PASS — class major version 65 (Java 21) | Inspected compiled `CastlyApplication.class` with `javap -verbose` |
| `npm run typecheck` | PASS | [Log](logs/typecheck.log) |
| `npm run lint` | PASS, no lint warnings | [Log](logs/lint.log) |
| `npm test` | PASS — 27 tests | [Log](logs/unit.log) |
| `npm run test:sql` | PASS — 25 authorization assertions on PostgreSQL 17 | [Log](logs/sql-policies.log) |
| `npm run test:backend` | PASS — rendered browser + real Java HTTP/JWT/JWKS + PostgreSQL | [Log](logs/real-api-browser.log) |
| `npm run test:legacy-backend` | PASS — preserved M1 Auth/REST/Storage contract | [Log](logs/legacy-m1-browser.log) |
| Connected-mode Expo web export | PASS | [Log](logs/connected-web-export.log) |
| Expo Android/iOS/web export, mock mode | PASS — bundles/assets only, not native builds | [Log](logs/all-platform-export.log) |
| PNG decode/dimensions | PASS — 8 PNGs, all 390×844 | [Sizes and SHA-256 manifest](screenshots.json) |
| Actual project's public JWKS probe | BLOCKED — HTTP 403 | [Exact error](logs/hosted-jwks-blocked.log) |
| Hosted Realtime delivery | NOT TESTED | No approved hosted migration or gateway validation |
| Native Android/iOS execution | NOT TESTED | No device or emulator used |

Java tests cover actual RSA/EC signature verification and invalid issuer/audience/expiry/role/signature rejection, configuration guards, role-limited PostgreSQL CRUD, participant isolation, protected-field rejection, transaction-local identity reset, CORS and concurrent booking decisions. Controller tests use Spring test JWT principals; the separate crypto tests and browser suite exercise real signature decoding.

The browser workflow creates/edits/publishes talent, saves favourites, creates/accepts/cancels a booking, creates a conversation, sends messages from both accounts, checks history, rejects an outsider and disables sending after cancellation. Only Auth/JWKS are local fixtures; business endpoints and PostgreSQL are real. The legacy M1 test intercepts Auth/REST/Storage requests and does not contact a hosted project. SQL DDL setup uses the owner of a **disposable local test container**; API traffic and integration tests use `castly_api` exclusively.

Node emits its existing `MODULE_TYPELESS_PACKAGE_JSON` warning during tests; the 27 tests pass. Maven needed workspace-installed JDK/Maven and temporary Cloud proxy/trust-store settings; those tools/settings are not committed.

## Screenshots

- [Accepted booking — 390×844](accepted-booking-390x844.png)
- [Conversation — 390×844](conversation-390x844.png)
- [Preserved M1 signup](legacy-m1/signup-390x844.png), [login](legacy-m1/login-390x844.png), [onboarding](legacy-m1/onboarding-390x844.png), [account](legacy-m1/client-account-390x844.png), [talent editor](legacy-m1/talent-editor-390x844.png), [offline recovery](legacy-m1/offline-390x844.png).

These show synthetic test users/data. Conversation capture intentionally displays unavailable live updates because the local fixture does not provide the Supabase Realtime gateway.

## Setup and rollout prerequisites

Full commands and endpoint contract: [backend setup guide](../../backend/README.md). Configuration templates: [server](../../backend/.env.example) and [mobile](../../.env.example).

1. Install JDK 21, Maven 3.9+, Node 22.13+ and Docker. Run `mvn clean verify` inside `backend/`; it recreates only the dedicated local `castly_spring_test` database. Run the four requested npm checks from the repository root as documented above, plus SQL/browser tests for the complete regression gate.
2. Before any hosted cutover, obtain approval for the forward migration. Review existing hosted policies/functions and data; unexpected permissive policies must be reconciled. After approval, the database owner applies versioned migrations, enables `castly_api LOGIN` and provisions its password via a secure prompt/secret manager. Do not supply owner or service-role credentials to Spring or Expo.
3. Configure server-only `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `SUPABASE_JWT_ISSUER`, audience, signing mode and CORS. Use verified TLS for remote PostgreSQL. The API does not load dotenv automatically; export environment values before `mvn spring-boot:run`.
4. Run the read-only `npm run test:jwt-config` from an authorized environment. The configured project's public JWKS returned `curl: (22) The requested URL returned error: 403` here. Confirm actual signing mode and validate a real development user's access token against a protected API route. Hosted signing compatibility is **not claimed**.
5. Configure Expo `EXPO_PUBLIC_API_URL` and `EXPO_PUBLIC_BUSINESS_API_MODE=spring`, retaining Supabase Auth/Storage variables. Android emulator uses `http://10.0.2.2:8080`; a physical device needs a reachable development address. Production requires HTTPS. Never put database passwords or signing secrets in public variables.
6. Validate hosted Realtime with two participant accounts and an outsider after approved database setup: accept booking, send both ways, reconnect/background/foreground, cancel, verify history retention and send denial. RLS/publication SQL is tested locally, but actual gateway delivery is not.
7. Use Expo Go or a development build on real Android/iOS hardware/emulator to check secure session restoration, deep links, native media/date selection, keyboard/safe areas, AppState subscription recovery and device network/TLS. Expo exports are not evidence of native execution.

The optional direct-Supabase fallback is for Metro development against a **separate pre-cutover development database**. It is rejected in production builds. Do not restore direct business-write privileges on the API database to enable it.

## Remaining limits

Lists are bounded/paginated; discovery currently fetches up to 200 profiles/1,000 portfolio rows, and the existing Search UI retains its ₹30,000/day budget ceiling. Booking/conversation list pages cap at 100; message history defaults to 50 with older-page cursors. Account summary counts remain bounded to the fetched results. The API supports server-side discovery filters. No payments, push notifications, delivery/read receipts, attachments or production deployment are included. Hosted Auth signing, pooler/TLS connection, Realtime and native device regression remain rollout gates.
