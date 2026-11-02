# Milestone 5 — review, diagnostics and local/device verification

Branch: `feat/milestone5-booking-messaging-notifications`. Based on `52bd178` (contains the specified `f58a259` integration and the owner's merged issue-1 corrections). Cloud work is isolated in `/workspace/castly-milestone5`. The Windows `castly-milestone4` worktree and other Cloud worktrees were not reset, cleaned or overwritten. No hosted database, password, permission, ledger or cutover was changed.

## Acceptance 403: findings and limits

The supplied response has `type: about:blank`, `detail: Not permitted` and an endpoint `instance`. In this code that is the `ApiErrors.database` translation of PostgreSQL SQLSTATE **42501**, rather than the controller's explicit wrong-Talent message or resource-server JWT error. Correct ownership/role alone does not establish database grants, RLS or trigger behavior.

The unmodified 67-test backend suite passes locally, including Talent acceptance. The **exact hosted statement is not knowable from that generic response alone**; no hosted token, PostgreSQL error or policy dump was available. Do not claim the hosted booking is fixed solely from these local tests. Sanitized server diagnostics were requested while implementation continued.

A specific failure was reproduced on disposable PostgreSQL: remove only the API conversation INSERT policy, keep the correct Talent/requested booking and the read policies. Acceptance updates status and then attempts `INSERT ... ON CONFLICT DO NOTHING`. PostgreSQL evaluates INSERT RLS even for an existing conflicting conversation. This produces the same 403 and rolls back the whole transaction. That unnecessary insert can block reuse of a legacy conversation that the participants are already authorized to read.

Fix: `ensureConversation` first selects the exact authorized Client/Talent pair. It reuses that conversation without an INSERT. If none exists, it still inserts under the same restricted role, accepted-booking RLS and transaction, with uniqueness handling for concurrent acceptances. **There is no privilege bypass.** A missing policy for a genuinely new conversation still fails closed and rolls back both status and notification event. Tests also deny the Client and an unrelated Talent, verify reuse/uniqueness/idempotency and atomic rollback.

Database steps now emit a random diagnostic `reference`, stable `operation` and SQLSTATE and exception/cause class names to server logs, without SQL text, parameters, account IDs, tokens or connection strings. The API includes reference/operation while retaining a generic human error. Expected codes include:

| Operation | Inspect with owner/read-only evidence |
|---|---|
| `booking.accepted.lock` | Booking SELECT, UPDATE(status), participant SELECT/UPDATE RLS |
| `booking.accepted.update` | Transition guard, immutable fields, account-role lookup, timestamp trigger |
| `conversation.reuse` | Participant conversation SELECT grant/RLS |
| `conversation.insert` | INSERT(client_id,talent_id), accepted-pair INSERT RLS, UUID defaults/trigger permissions |
| `conversation.lookup` | Participant SELECT after creation/conflict |

Owner can run `acceptance-diagnostics.sql` **read-only** through an approved connection and share sanitized definitions/grants plus the correlated server line. Do not paste credentials or user tokens. The owner subsequently confirmed conversation SELECT, INSERT(client_id,talent_id) and the accepted-pair INSERT policy already exist. Those privileges are sufficient in the local regression; **no acceptance policy rewrite or additional INSERT grant is proposed**. Explicit tests also prove table-wide INSERT and caller EXECUTE on attached trigger functions are unnecessary. Scalar DEFAULT functions are different: their schema/EXECUTE privileges may matter, so the read-only inventory also lists conversation defaults and dependent-function privileges. The lock, status UPDATE and conversation INSERT can each generate the supplied generic response; the actual hosted operation remains unconfirmed until its correlated trace is available. Do not change a correctly configured conversation policy on speculation.

## Booking and messaging behavior

Existing endpoints remain authoritative: Client requests; relevant Talent accepts/rejects; Client cancels requested/accepted; Talent cancels accepted. History includes existing declined/cancelled/completed records. There is **no complete endpoint or new completion transition**. Confirmations precede accept/reject/cancel; retry/loading/empty states and pagination remain.

Only participants with an accepted booking can send. History remains readable after cancellation. Chat supports older pages, timestamps, automatic bottom scrolling, a jump-to-latest control when reading history and offset preservation when older content is prepended. Failed network sends preserve drafts and do not automatically retry uncertain writes. Shared Realtime invalidations use one participant channel and reference-counted focused conversation channels, bounded event deduplication, burst coalescing, reconnect/foreground refresh and cleanup. Inactive screens stop watching. A single foreground poll reconciles at 60 seconds when connected, 15 seconds when unavailable; no background polling.

## Proposed persistent notification schema — approval required

New additive migration: `supabase/migrations/20261102100000_in_app_notifications.sql`. **Prepared and tested locally only. Do not run hosted migration/cutover without explicit approval.** Historical migrations are unchanged.

- Adds indexed recipient-owned notification rows and unique `(recipient_id,event_key)` deduplication.
- AFTER business-event triggers insert request/accepted/rejected/cancelled/message notifications in the same transaction; no separate worker or hosted infrastructure is needed. An aborted acceptance/message produces no notification.
- Trigger functions are narrow **SECURITY DEFINER**, `search_path=''`, owned by the trusted migration role. They only read verified business-row participant IDs and insert typed notifications, never modify booking/message authorization. EXECUTE is revoked from PUBLIC/anon/authenticated/castly_api. Review function ownership and definitions before approval.
- The API role gets SELECT and **UPDATE(read_at) only**, under recipient RLS; it cannot insert/forge notification events or alter recipients. Ordinary authenticated users get recipient-only SELECT for Realtime; all notification writes still go through Spring. Anonymous access is denied.
- No historical backfill, raw message snippets, push tokens, service-role key, broad role grants or read receipts. New events start recording after approved migration application; the API flag controls exposure, not trigger recording. Unattributed administrative status changes are not presented as participant actions.
- Notification read state is private. Message unread indicators count the recipient's unread message notifications. Viewing latest conversation content marks those notification rows read through the API; the sender cannot observe this as a message read receipt.

`CASTLY_NOTIFICATIONS_ENABLED=false` is the server default. Existing bookings/messaging and acceptance diagnostics work without applying the new schema. Disabled/missing storage returns `enabled:false` from unread stats and a clear unavailable feed; the app does not invent a zero-count inbox or subscribe to an absent notification table. Only after owner approval, staging application, ACL/trigger checks and tests should the server flag be set to true. API clients never apply DDL. If the flag is true but permissions drift, return a real error rather than showing empty success.

New private API endpoints:

- `GET /api/v1/notifications/unread-count`: capability + own total/message counts.
- `GET /api/v1/notifications?limit=50&before=UUID`: descending timestamp/ID cursor, own rows only.
- `POST /api/v1/notifications/{id}/read`: idempotent own read marker.
- `POST /api/v1/conversations/{id}/notifications/read`: participant-checked own message-notification markers.

Conversation reads optionally include `unread_notifications` when storage is enabled. No existing endpoint is removed. Notification navigation opens the related booking/conversation and the destination independently enforces authorization.

## Approval/application/rollback order

1. Finish acceptance diagnosis with the new operation/reference and read-only inventory. Check the known requested booking with a real Talent session after deploying only to an approved local environment. Do not change roles/policies just to hide a 403.
2. Restore the current schema/data to an approved staging Supabase project; validate rollback/PITR and Storage backup strategy. Inspect the new table, trigger ownership, function ACLs, RLS, indexes and Realtime publication. No `supabase db push` or ledger repair against hosted baseline.
3. Obtain explicit approval for the additive notification migration (and any separately reviewed acceptance repair only after the failing operation is established). The owner applies the exact reviewed artifact through the existing migration process. Do not replay historical repository migrations on the already reconciled host.
4. Verify ordinary/anonymous denial, recipient SELECT, API read_at-only writes, event deduplication, transaction rollback and message participant authorization on staging. Test hosted Realtime delivery separately; publication membership alone is not proof.
5. Enable the server notification flag only after those checks. Keep database credentials server-only and remote JDBC `sslmode=verify-full` with the correct trusted CA and dedicated `castly_api` login. Follow `docs/MOBILE_SPRING_SETUP.md` for Windows/Expo networking; physical Android uses Windows LAN IP, not localhost/10.0.2.2.
6. Rollback a code/UI exposure issue by stopping traffic and disabling the server flag; retain event data. The flag does **not** stop trigger recording. If event storage itself must stop, an owner must separately approve disabling only the two new event triggers after assessing missed-event implications. Do not drop data, loosen RLS, enable legacy bypass or alter existing business grants. Any schema restore/data reconciliation requires a tested owner-led plan; no automatic down migration is supplied.

## Local commands

Install Node/Maven/JDK21/Docker, then in this isolated review checkout:

```sh
npm ci
npm run typecheck
npm run lint
npm test
npm run test:components
cd backend
mvn clean verify
# Optional prepared-schema fixture path:
CASTLY_TEST_SCHEMA_PATH=reconciliation mvn clean verify
cd ..
npm run test:sql
npm run test:reconciliation
```

All database tests use named disposable Docker databases, never a hosted URL. Component tests use the actual React component trees with native bridge hosts mocked; they are not physical-device tests. `react-test-renderer` is development-only and pinned to the app React version.

For connected browser tests export with synthetic client config as in the Milestone 3 guide. This Cloud run used preview **8096** and mock preview **8092**, preserving other running previews. Set `TEST_URL=http://127.0.0.1:8096` for `test:backend`/`test:mobile-api`; the real suite uses Java API 8080, local Auth/JWKS fixture 8098 and enables notifications only on the disposable test DB. Run real API tests serially with Java DB tests (they reset the same local fixture). Legacy tests use a separate Metro server on 8097 with explicit isolated fallback acknowledgement. No credentials or actual user tokens enter artifacts.

## Manual Expo Go smoke test

Use disposable local/staging data by default. Retrying the known hosted booking can update it and requires the owner's explicit approval; no hosted mutation is performed by this task.

1. Use a separate Windows worktree/feature branch, leaving `C:\Users\shubh1504613\castly\castly-milestone4` and untracked files untouched. Start JDK21 Spring with the approved trusted JDBC CA, dedicated login, exact Supabase issuer/audience/signing mode and correct LAN API URL in that worktree's ignored mobile configuration. Keep notifications disabled until schema approval/application.
2. Sign in as the verified Talent and Client on separate sessions. Open the known request. Confirm acceptance and correlate any failure's reference/operation with server logs. Verify status rollback and no conversation/notification on denial. Verify Client/another Talent cannot accept it.
3. Client creates a fresh request; Talent sees it in incoming bookings. Exercise details, confirm accept, accepted/history filters and pagination. Reject another request, cancel a pending request as Client and accepted requests as either participant. There is no completion action.
4. With approved notification storage enabled, refresh Talent Notifications and open a request from its notice. Client receives one accepted/rejected/cancelled notice. Repeated idempotent decisions do not duplicate events; counts/notice read markers remain private to the recipient.
5. Open the accepted conversation. Send both ways; check timestamps, notifications, unread indicators and jump-to-latest behavior. Scroll into history, receive a new message without a forced jump, prepend an older page without losing position, then return to latest. No sender read receipts should appear.
6. Cancel while messaging: previously authorized sends may commit before cancellation; sends after cancellation fail and preserve the draft. History remains accessible. A new accepted booking for the same pair can re-enable sending and reuse the one conversation.
7. Use an unrelated user/direct URL to verify 404/denial for bookings, conversations, messages and notifications. Anonymous requests cannot see notifications. No direct Supabase business writes should occur in Spring mode.
8. Disconnect/reconnect, background/foreground and restart Expo Go. Confirm unavailable live status, poll/manual recovery, no duplicate messages/notices, preserved pagination, and no lingering subscriptions after changing accounts. Failed writes are not shown as successful; inspect server state before manually retrying an uncertain POST.
9. Test confirmation dismissal/disabled controls, TalkBack, large fonts, safe areas and keyboard on Android. Record actual device/OS and real hosted results separately; Cloud browser/component/export checks do not establish native/hosted readiness.
