# Castly Milestone 5 — implementation and validation report

Review branch: `feat/milestone5-booking-messaging-notifications`.
Base: `52bd178b02646d411e87c752fc7b5be3c483a8ef`, including the requested `f58a259` baseline and merged issue-1 corrections.
Validation date: 2026-11-02. All database writes were to disposable local Docker PostgreSQL fixtures. No hosted migration, permission change, cutover, deployment, merge, payment or push-notification integration was performed.

## Readiness and acceptance blocker

**Local implementation and regression checks pass. Hosted release readiness remains BLOCKED: the precise hosted booking-acceptance 42501 operation has not yet been observed.** Hosted notifications also require explicit schema approval and staging verification. No Android device/emulator was used.

The supplied `403 / Not permitted` is the existing translation of PostgreSQL SQLSTATE `42501`. Correct Talent ownership and the supplied conversation grants/policy do not distinguish the failing statement. Those exact narrow conversation privileges work in the local regression. It would be incorrect to report a missing conversation INSERT grant or policy as the hosted root cause.

The acceptance transaction now labels each database step and logs only a random support reference, operation, SQLSTATE, exception class and cause class. The mobile error displays the reference, including inside the confirmation dialog. No raw PostgreSQL message, SQL parameters, account IDs, tokens, credentials or connection string are logged by this diagnostic handler.

| Operation | Exact SQL executed by the corresponding step |
|---|---|
| `booking.accepted.lock` | `select * from public.bookings where id=:id for update` |
| `booking.accepted.update` | `update public.bookings set status=cast(:status as public.booking_status) where id=:id returning *` |
| `conversation.reuse` | `select * from public.conversations where client_id=:client and talent_id=:talent` |
| `conversation.insert` | `insert into public.conversations(client_id,talent_id) values(:client,:talent) on conflict(client_id,talent_id) do nothing` |
| `conversation.lookup` | Same pair SELECT after insertion/conflict resolution |
| `booking.accepted.transaction` | Outer fallback for claim binding/transaction completion failures outside the tracked statements |

Reproduced exact local failures, with the same public 403:

- Revoke only booking `UPDATE(status)`: the **SELECT FOR UPDATE** fails at `booking.accepted.lock` with 42501.
- Add a restrictive booking UPDATE CHECK rejecting the accepted row: the **UPDATE RETURNING** fails at `booking.accepted.update` with 42501.
- Remove only the conversation INSERT policy: the **INSERT ON CONFLICT DO NOTHING** fails at `conversation.insert` with 42501. Status and acceptance notification both roll back.

Local corrective change: reuse an already authorized conversation before attempting INSERT. PostgreSQL checks INSERT RLS even when `ON CONFLICT DO NOTHING` would reuse an existing row. The new path removes that unnecessary INSERT for existing pairs, while a new conversation still requires the same accepted-booking policy, two-column INSERT privileges and transaction. Concurrent/idempotent acceptance keeps one conversation. This fixes a demonstrated reuse failure; it is **not proof of the hosted cause**.

Required next evidence: one correlated sanitized `Business DB failure` line from an approved reproduction, plus the read-only inventory in [acceptance-diagnostics.sql](../../docs/milestone5/acceptance-diagnostics.sql). The hosted fix must follow that observed operation; **no speculative acceptance grant or policy rewrite is proposed**. Trigger caller EXECUTE and table-wide conversation INSERT are unnecessary in the regression. Scalar DEFAULT functions can require schema/EXECUTE privileges and are included in the inventory.

## Implemented behavior and files

- Booking list/details retain existing authoritative endpoints and pagination. Incoming/submitted labels, history states, accessible status badges and explicit accept/reject/cancel confirmation were added. Existing completed records remain view-only; no completion endpoint or new state transition was invented.
- Messaging retains participant authorization and accepted-booking gating. Older-page scrolling preserves position, latest messages scroll only when appropriate, and a jump-to-latest action appears while reading history. Failed sends preserve drafts and require an explicit retry. Timestamps and private notification-based unread counts are supported; no sender read receipts were invented.
- New recipient-only notification feed supports request, acceptance, rejection, cancellation and message events, pagination, private read markers, retry/unavailable states and booking/conversation navigation. Destination APIs independently authorize access.
- Shared Realtime manager owns one participant channel and reference-counted focused conversation channels, with bounded deduplication, event coalescing, reconnect/foreground reconciliation and cleanup. Foreground polling is shared: 60 seconds connected, 15 seconds unavailable. Events invalidate authorized API reads; they do not become trusted UI records directly.
- Existing Lavender Pearl colors, typography, components, navigation and demo behavior are retained. No unrelated screen redesign.

Primary changed areas:

| Area | Files |
|---|---|
| Backend acceptance and safe diagnostics | `ActivityController.java`, `ApiErrors.java`, new `DatabaseStep.java`, `BusinessApiTest.java` |
| Notification API and feature gate | New `NotificationController.java`, `NotificationService.java`, `application.yml`, backend `.env.example` |
| Shared mobile synchronization | New `liveHub.ts`, `LiveProvider.tsx`, `NotificationProvider.tsx`, updated `useLiveRefresh.ts`, app providers |
| Typed service and presentation | `api.ts`, `types.ts`, `LiveActivity.tsx`, new `notifications.ts`, `chatScroll.ts`, `LiveNotifications.tsx`, `DecisionDialog.tsx`, `BookingStatus.tsx`, `NotificationBell.tsx` |
| Notification entry points | Discovery, notification route, tab layout, shared bottom-navigation badge rendering |
| SQL preparation | New `20261102100000_in_app_notifications.sql`, `supabase/tests/notifications.sql`, local-only fixture scripts |
| Tests and delivery | New component/service tests, expanded Spring E2E, docs/milestone5, this report and logs/screenshots |

## API and database review

Added authenticated Spring endpoints:

- `GET /api/v1/notifications/unread-count`: capability plus own total/message counts.
- `GET /api/v1/notifications?limit=50&before=UUID`: recipient-only timestamp/ID cursor.
- `POST /api/v1/notifications/{id}/read`: idempotent own read marker.
- `POST /api/v1/conversations/{id}/notifications/read`: participant check, then own message-notification markers.

Conversation reads optionally include `unread_notifications`. Existing business endpoints remain compatible. Mobile business writes still use Spring; Supabase remains Auth, Storage and authorized Realtime.

The additive notification migration is **prepared, not applied to hosted Supabase**. It adds recipient RLS, indexed pagination, unique event deduplication and two narrow transactional AFTER-event triggers. API grants are SELECT plus UPDATE(read_at), with no API insert/recipient modification. Authenticated gets own SELECT only for Realtime; anonymous is denied. Trigger functions use empty search_path, revoked caller EXECUTE and a trusted migration owner. They can insert typed notifications only, without modifying business authorization. No message bodies or historical backfill are stored.

`CASTLY_NOTIFICATIONS_ENABLED=false` remains the default. Without approved schema the app shows feature unavailable and existing booking/messaging continues. After migration approval, events begin recording even if the API exposure flag remains false. The flag is not a database rollback. See the explicit owner-led staging, application, permission checks and rollback steps in [the guide](../../docs/milestone5/README.md).

## PASS / FAIL / BLOCKED checklist

| Check | Result | Evidence |
|---|---|---|
| Unmodified backend acceptance baseline | PASS — 67 tests | `logs/priority0-baseline.log` |
| Java 21 `mvn clean verify`, fresh schema | PASS — 72 tests, zero failures/errors/skips | `logs/backend.log` |
| Java prepared/reconciled schema path | PASS — 72 tests | `logs/backend-reconciled.log` |
| Valid Talent, Client/other Talent denial, reuse, rollback, narrow privileges | PASS | Spring integration tests and operation diagnostics |
| `npm run typecheck` | PASS | `logs/typecheck.log` |
| `npm run lint` | PASS | `logs/lint.log` |
| `npm test` | PASS — 48 tests | `logs/unit.log` |
| `npm run test:components` | PASS — 2 tests, native bridge hosts mocked | `logs/components.log` |
| `npm run test:sql` | PASS — 37 policy assertions | `logs/sql.log` |
| `npm run test:reconciliation` | PASS — 286 assertions/catalog gates | `logs/reconciliation.log` |
| `npm run test:backend` | PASS — real Java/JWT/JWKS/PostgreSQL/browser journey | `logs/e2e.log` |
| `npm run test:mobile-api` | PASS — deterministic HTTP regression fixture | `logs/mobile-browser.log` |
| `npm run test:legacy-backend` | PASS — isolated development fallback fixture | `logs/legacy.log` |
| `npm run test:browser`, `test:portraits`, `test:ui` | PASS — demo regression, ten screens/three widths | Separate named logs |
| `npm run test:screenshots` | PASS — existing 30 PNGs fully decoded | `logs/screenshots.log` |
| `npm run test:accessibility` | PASS — existing browser checks, including 150% text simulation | `logs/accessibility.log` |
| New M5 screenshot decoding | PASS — 8 PNGs, nonzero expected dimensions and SHA-256 manifest | `screenshots/manifest.json` |
| Expo export web/iOS/Android bundles | PASS — bundle exports, not native builds | `logs/export.log`, `logs/spring-export.log` |
| `npm run check:client-env` | PASS — no privileged public client keys | `logs/client-env.log` |
| `npm run test:jwt-config` against public host | BLOCKED — `Public JWKS verification failed: curl: (22) The requested URL returned error: 403` | `logs/jwt-config.log` |
| Exact hosted acceptance statement/root fix | BLOCKED — no correlated hosted exception/operation | Diagnostic preparation complete; no host mutation |
| Actual hosted Realtime delivery | NOT RUN | Subscription logic mocked; browser suite uses foreground reconciliation |
| Physical/emulated Android, TalkBack, keyboard/safe areas | NOT RUN | Manual steps below |

Backend commands used JDK21/Maven3.9.11 with the local dependency cache and disposable DB fixture. Browser commands used isolated previews: connected8096, demo8092, explicitly acknowledged legacy8097. Auth/JWKS in E2E are local fixtures, not actual hosted authentication. Live socket reconnect/dedup/cleanup are unit tests; do not interpret browser flow success as a hosted Realtime delivery test. The reconciled-schema run preceded the final outer transaction diagnostic wrapper; the fresh-schema run includes that wrapper. Later changes affected only mobile error display, ordering tests and screenshot animation settling.

## Screenshots and visual review

Eight fresh Chromium screenshots use synthetic local accounts/data, not hosted users: Notifications and acceptance confirmation at **360×800, 390×844 and 430×932**, plus accepted booking and conversation at **390×844**. The manifest records full decoding, dimensions, bytes and hashes. Finite animations are settled during capture. These are repo-contained files under `artifacts/milestone5/screenshots/`, not references to missing `/tmp` artifacts.

The screens preserve Lavender Pearl surfaces, dark text, purple actions, Manrope hierarchy and rounded cards. Responsive checks found no horizontal overflow and confirmation actions within each viewport. Browser text simulation and component touch-target assertions passed. This is not a claim of pixel parity against a new design or native accessibility certification.

## Manual Expo Go / remaining release gates

Follow [docs/milestone5/README.md](../../docs/milestone5/README.md#manual-expo-go-smoke-test) for exact nine-step smoke testing and migration order. In brief:

1. Keep the owner's Windows worktree/untracked files untouched; use a separate branch/worktree. Configure JDK21 Spring with dedicated `castly_api`, verified JDBC CA, actual JWT issuer/audience/signing mode and LAN API address. Keep notification flag off until approval.
2. Use disposable local/staging Client and Talent sessions. Create a request, verify incoming/details, confirm acceptance and conversation reuse; test reject/cancel/history and unrelated-user denial. A known hosted booking retry can mutate it and needs separate approval.
3. After approved staging migration, verify one recipient notice per event, private read markers/counts and navigation; confirm true Realtime delivery/reconnect rather than polling alone.
4. Exchange messages, paginate older history, receive while scrolled up, return to latest, test offline draft retry, cancel while messaging and retained authorized history. Restart/background/logout and verify cleanup.
5. On a recorded actual Android device/emulator test TalkBack, large text, keyboard, modal dismissal, safe areas and touch targets. Physical Android uses the development machine's LAN IP; localhost is the phone itself, and 10.0.2.2 is emulator-only.

No production readiness claim is made until the hosted acceptance diagnostic, hosted configuration/Realtime checks and native smoke tests are completed. No branch was merged and no hosted project was changed.
