# Issue #3 — Milestone 3 mobile/Spring integration

Date: 2026-11-02. Branch: `feat/phase2-mobile-spring-integration`. Worktree: `/workspace/castly-issue3`.

## Base selection and safety

Started from `fix/issue-1-booking-security` at `4726065`, after comparing it with `feat/phase2-bookings-messaging` at `45f558e`. The integration branch already merged the earlier issue-1 work; the latest fix branch adds three owner reconciliation corrections and otherwise preserves that same implementation. Using the fix head preserves both workstreams without rebasing, resetting or merging the owner's worktree. The proposed draft PR base is `fix/issue-1-booking-security` so it shows only Milestone 3 changes.

The original `/workspace/castly-mobile` worktree remains clean at `00ea127`; no original source or environment file was edited. No Supabase SQL/migration/permission/password/ledger file, including `03-cutover.sql`, changed relative to the selected base. All SQL execution was inside disposable local Docker databases. No hosted mutation, production deployment, actual mobile environment switch, main change, merge, payments or new category scope was performed.

## Audit and implementation notes

Most M1/M2 screens already called the typed Spring services. This work completes integration gaps rather than rebuilding or redesigning those screens.

| Existing gap | Change and evidence |
|---|---|
| Direct database service factory instantiated in Spring mode; legacy activity object could retain unsupported Spring writes | Split Supabase Auth/Storage into `platform.ts`; lazy `composition.ts` builds legacy business services only in explicit fallback. Unsupported legacy live actions reject. Tests forbid `.from()`/RPC/business fallback in Spring mode, including failures. |
| Fallback had a development check but no explicit isolated-DB acknowledgement | Require Metro development plus `EXPO_PUBLIC_ISOLATED_SUPABASE_FALLBACK=true`; production/export fallback remains forbidden. No automatic failover after API errors. |
| API-origin/device setup unclear; token refresh could duplicate; terminal 401 left account error loops | Safe origin-only URL validation, documented emulator/simulator/LAN addresses, shared refresh rotation, subject-targeted expiry notice and protected screen identity keys. Public reads omit stale bearer tokens; private 403/offline failures remain explicit. |
| Late response or delayed form could cross an account change | Compare subject again before applying successful private results; bind own-account/profile/favourite/portfolio and booking/message writes to the form's original subject. Caller IDs are client-side stale-form guards only, never server authority or DTO fields. Test confirms account A's form sends no request under account B and does not expire B. |
| Catalog loaded only first Spring page (default 100 profiles / 1,000 assets); category API unused | Traverse bounded stable pages of 200, load categories from Spring and map the five supported categories into the existing UI. Search/filters/sorting reuse the complete loaded public catalog. Typed server query filters remain exposed/tested. Nonadvancing/oversized catalogs fail explicitly rather than silently truncate. |
| Shared profiles/galleries depended on prior catalog navigation; one failed media signing request hid all talent | Direct published-profile hook and per-profile metadata reads with loading/error/retry/not-found states. Media failures keep text visible with fallback/retry and no stock substitution for connected talent. |
| Portfolio captions/order not editable in UI; uncertain metadata registration could delete an uploaded object already referenced by a committed write | Existing Lavender Pearl fields/buttons now edit metadata through Spring. Keep uploads private on unconfirmed registration; reload before retrying, rather than deleting a possibly referenced object. Account summary counts also traverse all available pages. |
| Cached early booking/conversation pages retained stale statuses; stale detail responses could leave actions visible | Reload all loaded pages, deduplicate, preserve Load more on overlapping full pages, include Completed filter, and invalidate detail data on errors/route changes. UI enforces participant/role/status restrictions and refreshes on 403/404/409 conflicts. |
| Chat/conversation availability did not react to booking UPDATE events | Realtime booking changes also invalidate those Spring reads; foreground/poll/manual recovery remains. History is hidden on denied/failed reloads; drafts survive send errors. Server authorization remains authoritative. |

Reused Expo Router routes, guards, Supabase client/session persistence, Storage/Realtime transport, validation helpers, layout primitives, theme tokens, portraits and the ten-screen Lavender Pearl composition. The only backend feature change is backwards-compatible pagination on public `GET /api/v1/portfolio` (`limit` 1–1000, default 1000; `offset` 0–100000), with a new Java integration test. No SQL change is needed.

## Acceptance and validation

Local implementation/regression: **PASS**. Hosted end-to-end/native readiness: **NOT VERIFIED**. Draft PR creation is attempted after pushing; see delivery section/final response for GitHub API outcome.

| Command / check | Actual result |
|---|---|
| `npm run typecheck` | PASS |
| `npm run lint` | PASS, no lint warnings |
| `npm test` | PASS — 41 tests |
| `npm run check:client-env` | PASS |
| Java 21 `mvn clean verify` (fresh repository history) | PASS — 67 tests, 0 failures/errors/skips |
| `CASTLY_TEST_SCHEMA_PATH=reconciliation mvn clean verify` | PASS — 67 tests, 0 failures/errors/skips |
| `npm run test:mobile-api` | PASS — deterministic Auth/Storage/Spring HTTP fixture; 201 profiles, deep links/gallery, category loading, catalog retry, same-user 401 refresh, 403, 101-booking pagination/refresh, onboarding/account, portfolio metadata edit, terminal expiry and zero direct PostgREST/RPC requests |
| `npm run test:backend` | PASS — actual Spring HTTP/JWT/JWKS/PostgreSQL/rendered app; profile draft/edit/publish, favourites, booking creation, acceptance/rejection, pending/accepted cancellation, conversation, two-party send/history, outsider denial and paused sending; same-user refresh and zero PostgREST bypass |
| `npm run test:legacy-backend` | PASS — explicitly acknowledged isolated development fallback, intercepted M1 Auth/REST/Storage contract |
| `npm run test:sql` | PASS — 25 fresh-history authorization assertions |
| `npm run test:reconciliation` | PASS — 286 local SQL assertions/catalog gates |
| `npm run test:browser` | PASS — category/search/filter/reset/profile/save/navigation/overflow |
| `npm run test:portraits` | PASS — bundled media, cropping bounds, loading/fallback |
| `npm run test:ui` | PASS — ten demo screens, three widths, 30 captures |
| `npm run test:screenshots` | PASS — 30 nonblank PNGs decoded at expected dimensions |
| `npm run test:accessibility` | PASS — controls, three-width sticky profile/footer, text scaling, date/keyboard/unread checks |
| Expo connected-fixture web export | PASS |
| Expo Android/iOS/web bundle export (mock) | PASS — bundles/assets, not native builds |
| `npm run test:jwt-config` | BLOCKED — `Public JWKS verification failed: curl: (22) The requested URL returned error: 403` |
| Actual hosted JWT/private request, Windows CA/pooler, hosted Realtime/Storage and native device | NOT TESTED — no real development-user token/successful hosted requests or device/emulator evidence |

Logs: [logs](logs/). The Maven commands used the workspace JDK21/Maven and cached dependencies (`mvn -o -Dmaven.repo.local=/workspace/.m2/repository clean verify` from backend). API tests connect only as the restricted local castly_api role; Docker owner credentials are fixture/DDL-only. The real-API browser suite uses local signed Auth/JWKS fixtures, not a real Supabase project; the deterministic mobile suite mocks HTTP contracts. Neither proves hosted authentication or Realtime delivery. Mock tests used an isolated mock export; fallback tests used a separate Metro development configuration. Expo client values were synthetic and never written to an owner environment file.

The legacy fixture initially lacked the newly consumed categories endpoint; it was updated and the suite passes. Node's existing module-type/Expo color warnings remain nonfatal. Reference screenshot churn from regression was restored; fresh local API captures are [accepted booking](screenshots/accepted-booking-390x844.png) and [conversation](screenshots/conversation-390x844.png), both verified PNGs at 390×844 with [hash/dimension manifest](screenshots/manifest.json). No tokens/credentials appear in the captures.

## Exact local verification setup

From this review worktree, install dependencies, then:

```sh
npm ci
npm run typecheck
npm run lint
npm test
npm run check:client-env
cd backend
mvn clean verify
# Optional second local schema path:
CASTLY_TEST_SCHEMA_PATH=reconciliation mvn clean verify
cd ..
npm run test:sql
npm run test:reconciliation
```

Docker/Node are required by Java integration tests. Existing SQL policy test container setup is documented in backend/README; tests never take hosted credentials. To reproduce connected browser checks, build the local fixture export:

```sh
EXPO_NO_DOTENV=1 EXPO_PUBLIC_BACKEND_MODE=supabase \
EXPO_PUBLIC_BUSINESS_API_MODE=spring \
EXPO_PUBLIC_SUPABASE_URL=http://127.0.0.1:8098 \
EXPO_PUBLIC_SUPABASE_ANON_KEY=sb_publishable_local_fixture \
EXPO_PUBLIC_API_URL=http://127.0.0.1:8080 \
npm run export:web -- --output-dir /tmp/issue3-spring-web
WEB_EXPORT_DIR=/tmp/issue3-spring-web PORT=8086 npm run preview
# Another terminal, after Java packaging; run these serially:
npm run test:mobile-api
npm run test:backend
```

`test:mobile-api` intercepts fixture HTTP and needs no Auth/API server. `test:backend` starts/stops its own local Java/Auth servers and resets only the disposable castly_spring_test DB. Keep ports 8080/8098 free. Test suites emit no user tokens. Set `CASTLY_JAVA` if the JDK executable is not on PATH. For legacy regression use Metro at 8087 with `EXPO_NO_DOTENV=1`, synthetic Supabase settings, business mode `supabase`, and the explicit isolated fallback acknowledgement; run `TEST_URL=http://127.0.0.1:8087 npm run test:legacy-backend`.

## Manual verification and remaining limits

[Windows Spring + trusted CA, device URL matrix, Expo Go Android and ordered manual end-to-end smoke test](../../docs/MOBILE_SPRING_SETUP.md) contains exact PowerShell/environment commands and checks. The owner must finish the correct JDBC CA/root.crt setup with `sslmode=verify-full`, use the dedicated non-admin login and approved pooler identity, inspect active signing configuration, and test a real development user on the phone. Do not disable certificate verification or use administrator/service-role credentials. Health/categories alone are not authenticated integration proof.

The complete smoke sequence covers two roles/accounts, onboarding/session restore/rename, draft/published profiles, direct media upload plus Spring metadata editing, discovery/filter/deep links/favourites, booking creation and all permitted decisions, conversation authorization, history/paging, cancellation while sending, Realtime reconnect/foreground, offline recovery, session expiry/account switching, native permissions/date/keyboard/font/safe areas. Windows commands and native behavior were not executed in Cloud. Hosted Realtime delivery and Storage policies remain owner/staging checks, with no database cutover in this PR.

Catalog traversal is bounded by the API offset limit and loads public metadata locally; very large catalogs should move to server-side search/sort paging in a later scoped change. Offset reads are not a database snapshot during concurrent insertions; refresh starts from the current first page and deduplicates. No automatic retry is made for an uncertain network write; inspect server state before manually retrying. Unconfirmed Storage uploads can leave private unreferenced objects for manual cleanup. Production API URLs must be trusted HTTPS; no native release cleartext exception was added. No new backend feature phases, payments, push notifications or delivery/read receipts are included.

## Draft PR delivery

The implementation, tests, logs and manual guide are ready for a draft review. GitHub API access was already returning Forbidden when reading issue #3; the issue was read through its public page. Draft creation will be attempted with GraphQL and REST after pushing, with base `fix/issue-1-booking-security`; the final handoff records the exact outcome. Prepared body: [pull-request.md](pull-request.md).
