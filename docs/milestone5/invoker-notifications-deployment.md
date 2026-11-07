# Milestone 5 notifications and Realtime — reviewed deployment report

2026-11-07. Branch: `feat/milestone5-booking-messaging-notifications`.

**Local security/regression gates pass. No hosted Supabase migration, deployment, booking write, permission change or unrestricted db push has been performed.** The working private-identity booking/conversation/message path is retained. No JWT keys, connection credentials, TLS settings, account roles, billing or unrelated screen layout changed.

## Exact forward migration and baseline

Apply only the reviewed artifact, after explicit owner approval:

`supabase/migrations/20261107170000_invoker_notifications.sql`

Required hosted baseline: the existing `private.request_user_id()` invoker helper and restricted castly_api business policies/guards are already installed, as reported; public.notifications is absent; Supabase's existing `supabase_realtime` publication is present. Do not reapply the private-identity migration or execute earlier proposed notifications/auth-grant migrations.

This artifact creates notification storage, recipient indexes/RLS, validated invoker event triggers and publication membership in one transaction. It supports the **known prior local proposal** as an upgrade without dropping its table/data, explicitly converting its two definer recorder functions to SECURITY INVOKER. It rejects unexpected columns/types, event uniqueness, table ownership or policy names for separate review. The reconciled Java fixture exercises the absent-table hosted baseline; fresh-history tests also exercise upgrade from the immutable old local proposal.

Historical migrations, including `20261102100000_in_app_notifications.sql`, remain byte-for-byte unchanged. That pending proposal uses auth.uid() and SECURITY DEFINER and is **superseded**, not a deployment prerequisite.

## Security design

All three new/final notification functions are **SECURITY INVOKER**, with empty search_path:

- `castly_booking_notification`: AFTER booking INSERT/status UPDATE, derives requested/accepted/declined/cancelled event and opposite recipient from the real row and transaction identity.
- `castly_message_notification`: AFTER message INSERT, validates sender/participant and derives the opposite conversation participant.
- `castly_notification_insert_guard`: BEFORE notification INSERT, rejects a direct API insert and validates nested trigger context, actor, source booking/message, recipient, source state, event kind and exact deduplication key.

The source booking transition and accepted-booking messaging guards remain authoritative and unchanged. Recorder functions return without manufacturing participant events for non-API administrative imports. Trusted object owners retain normal PostgreSQL maintenance authority; no invoker function borrows that authority for API users.

| Principal | Notification permissions |
|---|---|
| anon | None |
| authenticated (Realtime) | SELECT own recipient rows only; no writes, private schema access or trigger-function execution |
| castly_api | SELECT own recipient rows; UPDATE(read_at) own rows; INSERT only six event metadata columns, subject to the insertion guard and trigger-context RLS |
| Trusted migration/object owner | Existing owner authority for schema/maintenance; never the mobile/API login |

The six INSERT columns are recipient_id, kind, event_key, booking_id, conversation_id and message_id. INSERT(id/created_at/read_at), table-wide INSERT, DELETE, recipient reassignment and business-table TRIGGER privileges remain denied. Source booking UPDATE remains status-only. Public/anon/authenticated/API EXECUTE is revoked from the trigger functions; PostgreSQL invokes attached triggers without needing runtime caller EXECUTE on those functions.

An invoker trigger needs its caller's INSERT privileges; hiding that fact behind a definer function would violate the requested design. The API's column grant is therefore explicit and guarded. Ordinary end users never receive this SQL login or insert grant; there is no notification-create HTTP/RPC endpoint. Direct API inserts are rejected even if they reference a valid-looking existing business event. The guard also checks source facts instead of trusting trigger depth alone.

As with the existing private identity architecture, the server's database login remains a **trusted credential boundary**: an arbitrary SQL client possessing it can set custom JWT GUCs. Trigger depth is defense against erroneous/direct SQL, not cryptographic authentication of a compromised database login. Mobile clients use verified JWTs through Spring and authorized read-only Realtime, not that credential. No new function grants elevated privileges, role membership, service_role, BYPASSRLS or auth schema access.

### RLS, identity and Realtime

API read/update/insert policies and invoker functions use private.request_user_id(), bound by Spring's existing transaction-local JWT settings. Realtime runs under authenticated and cannot execute that private API helper; its recipient SELECT policy reads the platform's verified JWT subject settings inline using pg_catalog functions, without auth.uid() or managed-auth grants. RLS, rather than the client channel filter, is the access boundary.

Only notifications is added to the existing publication; no other table/role/publication settings change. Missing publication aborts the migration for owner review. The app subscribes to recipient-filtered INSERT/UPDATE events only after the API reports notification capability. Events invalidate authorized HTTP reads instead of becoming trusted UI records directly. Token changes, reconnects, foreground polling, event deduplication and channel cleanup are retained; late events from replaced channels are now ignored too.

### Deduplication and atomicity

The unique key `(recipient_id,event_key)` identifies one booking status event or message event. Repeated same-status updates do not emit a new event. Distinct messages, even identical bodies, remain distinct events. No message body/snippet, follower/review claim, device token or push feature is stored.

An important local failure was caught before delivery: ON CONFLICT DO NOTHING on an invoker insert addressed to another recipient required visibility incompatible with recipient-only SELECT RLS. The final recorders use a normal INSERT and catch **only** a unique_violation naming `notifications_recipient_id_event_key_key`; all other errors propagate. This retains deduplication without granting senders access to recipient rows. Tests repeat actual event triggers and verify one notice.

Notifications are inserted within the business transaction. Failed acceptance/conversation creation or rolled-back message sends leave neither a partial business change nor an orphan event. No automatic business-write replay was added. The sender cannot inspect the recipient's notification read state, and messages acquire no read-receipt fields.

## Spring and mobile behavior

Existing list, cursor pagination, unread-count, mark-one-read and conversation-notification-read endpoints are retained. Capability now requires the three expected invoker functions, so the old definer-only proposal is not treated as ready storage. `CASTLY_NOTIFICATIONS_ENABLED=false` stays the server default.

`POST /conversations/{id}/notifications/read` accepts an optional `through=<message UUID>` cursor. It validates the participant and message/conversation relationship before marking only the recipient's notifications through that ordered message. Updated mobile calls pass the latest displayed message, preserving newer ordered arrivals as unread. Existing clients without this optional parameter retain the prior read-all-in-conversation behavior. These are private notification markers, not proof that a person read a message or sender-visible receipts.

Lavender Pearl surfaces, Manrope typography, purple actions and existing navigation are reused. The feed supports:

- All five event titles, private unread state/counts and booking/conversation navigation.
- Explicit **Mark as read** without navigation; opening an unread notice still marks it read.
- Cursor history with the loaded page count retained on live refresh, rather than resetting to one page.
- Loading, empty, unavailable/offline and retry states; failed reads do not display cached private rows as a successful response.
- Subject-scoped snapshots/counts and existing expected-subject transport guards.

The existing Discovery bell, Messages unread badge, participant conversation list and shared Realtime manager are reused. Notification count failures disable the notification subscription until capability/count recovery; manual/foreground reconciliation remains available. Invalidations received during a count request schedule one follow-up read after that request, avoiding both parallel count queries and a stale response losing the event. No mobile business-mode, backend URL or credential file was switched.

## Regression gates and evidence

| Check | Final result |
|---|---|
| Java21 fresh-history `mvn clean verify` | PASS:88 tests, zero failures/errors/skips; legacy notification proposal upgraded to invoker design |
| Java21 reconciled `mvn clean verify` | PASS:88 tests, zero failures/errors/skips; notifications absent before this migration, matching hosted |
| SQL security suite | PASS:53 assertions, including the private-identity path and invoker notification tests |
| TypeScript `npm run typecheck` | PASS |
| `npm run lint` | PASS |
| `npm test` | PASS:50 service/logic/Realtime tests |
| `npm run test:components` | PASS:4 tests; real React trees with native bridge/router/HTTP fixtures |
| `npm run test:mobile-api` | PASS: deterministic browser HTTP regression fixture |
| `npm run test:backend` | PASS: actual local Java/JWT/JWKS/PostgreSQL/Chromium booking-to-messaging journey |
| Expo web export | PASS: fixture-only loopback configuration, no hosted credentials |
| Notification/confirmation screenshots | Eight fresh PNGs at360×800,390×844,430×932 plus accepted booking/chat390×844; fully decoded with dimension/hash manifest |

Security scenarios include anonymous/counterparty rejection, recipient-only lists/counts/cursors/read markers, denial of direct valid-looking API inserts and ordinary authenticated writes, inability to install booking triggers or disable validation, repeated actual triggers, all three invoker modes, publication membership, repeated acceptance, concurrent opposite senders, transaction rollback and identity isolation. Existing booking transition/cancel/send concurrency and negative JWT verification tests remain. The optional message cutoff has unauthorized/invalid-cursor and newer-message-unread regressions.

The native-renderer fixtures test explicit mark-read without navigation, preservation of loaded history on invalidation, notification navigation, unavailable/retry behavior and reconciliation of a count invalidation received while a request is pending. Realtime unit fixtures verify authentication, recipient filters, duplicate coalescing, reconnection, cleanup and stale-channel events. SQL tests independently verify authenticated recipient visibility without private-helper access.

All database tests use named disposable Docker databases; auth schema USAGE is denied to castly_api across both Java paths, and the new SQL security case reproduces that restriction within its rollback transaction. Fresh local history is not a recommendation to replay managed-auth grants on hosted Supabase. The reconciled fixture does not install the old notification proposal before the new migration.

Logs: `artifacts/milestone5/invoker-notifications/logs/`. Screenshots and manifest: `artifacts/milestone5/invoker-notifications/screenshots/`. Only synthetic local accounts are used. The existing fixture's inherited stdout produces a Surefire native-channel warning; all assertions run and both builds finish BUILD SUCCESS. Formatting/export tooling initially hit unwritable home caches and was rerun successfully with task-local cache directories; no credential or security setting changed.

**Limits:** hosted Realtime socket delivery is not verified by a mocked channel or publication assertion. The real browser E2E has local Auth/JWKS fixtures and no Realtime gateway, so it checks unavailable-live/manual reconciliation honestly. Hosted subscriptions, multi-device delivery and native Android/iOS/TalkBack behavior require approved staging/device verification. No actual device/emulator was used. Screenshots show fixture live-update unavailability, not a claimed hosted connection.

## Exact deployment order — approval gate remains

1. **Review and approve the new artifact and staging plan.** Read-only inventory must confirm the existing private helper/role grants, normal booking/message flow, notification table absence (or the exact known prior proposal), source guards and existing publication. Back up relevant schema/policy/function metadata and establish owner-controlled recovery. Do not use castly_api to execute schema migrations.
2. **Prepare the matching Spring release with `CASTLY_NOTIFICATIONS_ENABLED=false`.** This code can run against the current absent-table baseline without enabling the feed. Keep current verified JWT/TLS settings and API/client credentials. No mobile business API switch is needed. Stage the code through the approved release process; none has been deployed by this task.
3. **Apply only `20261107170000_invoker_notifications.sql`** as the trusted migration owner in the approved staging environment, then in hosted only after explicit approval and staging verification. It is a single transaction. Do **not** run unrestricted `supabase db push`, apply `20261102100000_in_app_notifications.sql`, execute the superseded auth-USAGE grant proposal, replay the private-helper migration, or repair/rewrite existing migration history. The owner must select this one version and record it through an approved migration workflow, explicitly handling older unapplied proposals; no mark-applied/ledger repair commands are supplied.
4. **Verify before exposure:** notification RLS/recipient policies; SELECT-only authenticated; API six-column INSERT + read_at-only UPDATE; no table-wide source UPDATE/notification INSERT or metadata mutation; all three functions invoker/pinned search_path with caller EXECUTE revoked; BEFORE insert guard and both AFTER recorders enabled; correct event uniqueness/indexes; notification publication membership; auth schema access still denied to the API role. Re-run owner-approved staging security tests. All five event types must have the correct opposite recipient and no duplicate rows.
5. **Enable notifications only after those checks**, by setting `CASTLY_NOTIFICATIONS_ENABLED=true` on the approved server release. Publish the matching mobile changes through the normal approved channel. Verify list/unread/read/navigation on two accounts, then actual Realtime INSERT/read-marker UPDATE delivery, reconnect/token refresh, background/foreground recovery and sign-out cleanup. Confirm recipient filters **and RLS** exclude other users. Test the working request→accept→conversation→two-way-message flow again with approved test data. Hosted retries can mutate data; no such test was performed here.
6. **Record actual staging/device evidence and obtain release approval.** Keep deployment/migration scope to this notification artifact and matching app/server changes. No production deployment, merge, payment, push-notification infrastructure or unrelated migration is included.

Event recording starts when the migration commits, **even while the API feature flag is false**. The flag controls exposure, not the database recorders. There is no historical backfill and no fabricated notice for administrative imports.

### Rollback / incident boundary

Disabling the server flag hides notification APIs/badges but does not stop database event recording. A notice failure propagates and rolls back its business transaction, preserving atomicity. If the recorders themselves block live work, the owner must approve a controlled incident action disabling **only the two notification AFTER recorders**, while retaining source booking/message authorization guards, the notification insertion guard, RLS and data. Such a pause creates an explicit event gap; no automatic replay/backfill is claimed.

Never disable the insert validation guard alone, turn off RLS, grant unrestricted writes, restore definer shortcuts, drop data with CASCADE or batch-apply older proposals as recovery. Any schema/code restore after commit requires an owner-reviewed coordinated plan. A failed migration transaction rolls back its own DDL; application flags are not a database rollback.

**Delivery state: implementation, security checks and local regressions pass; hosted migration/deployment and actual hosted Realtime validation await approval.**
