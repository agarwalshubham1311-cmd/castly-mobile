# Phase 6 — Notifications & Engagement (M6.1–M6.4) | Codex implementation

@codex Please implement **Phase 6 — Notifications & Engagement** for Castly, based on the existing milestone branch `feat/milestone5-booking-messaging-notifications` (Phase 4 trust + Phase 5 discovery). **Do not deploy, change hosted environment variables, apply hosted SQL, send real notifications, start paid services, or merge without explicit owner approval.** Work on a new branch `feat/phase6-notifications-engagement`, open a draft PR against milestone5, and provide executable tests and migration review docs.

## Goal
Improve booking responsiveness and retention while preserving privacy, consent, deliverability and the current Java 21 Spring Boot + Supabase PostgreSQL + Expo/React Native architecture. Do not bypass Spring business APIs or Supabase RLS. Reuse existing booking, messaging, notification, subscription and trust primitives; inventory them before changing schemas. No duplicate notification sends on event retries.

## M6.1 Push notifications
- Booking request, accepted/declined/cancelled, new message (participant-only; accepted-booking messaging rules), relevant reminders.
- Expo push token registration/unregistration per user/device, token rotation, logout/account switch handling; no token exposure between actors. Server-side sender with queue/outbox, idempotent event keys, retries/backoff, dead-letter and delivery receipt handling; invalid-token cleanup.
- Deep links to authorized booking/conversation only after auth; lock-screen content privacy defaults; foreground/background/killed behavior and Android 13+ notification permissions. Push is best-effort, not a source of truth.

## M6.2 Email notifications
- Booking and account activity, opt-in reminders; verify address and enforce consent/preferences, suppression/unsubscribe for optional email; distinguish mandatory transactional vs marketing.
- Provider abstraction (disabled/no-op default; credentials only in secrets), templated branded email, safe links, retry/idempotency, no secrets or sensitive message bodies in logs. SPF/DKIM/DMARC setup instructions, sandbox integration tests; no live sends without approval.

## M6.3 Engagement
- Saved searches with ownership and RLS, saved-search alert preferences and rate-limited digest; booking reminders and opt-in re-engagement with timezone/quiet-hours and frequency caps.
- Consent, opt-out, delete/export, no spam, no private trust/evidence data, avoid using inferred sensitive traits. Account switch isolation and blocked/unpublished creator exclusion. Deterministic scheduling and deduplication.

## M6.4 Analytics
- Privacy-minimized server-authoritative events for search→profile→save→request→accept→message→complete, notification delivered/opened, activation, weekly retention; dedupe, anonymous aggregation, retention policy.
- Admin-only aggregate dashboards / API, role-checked; no PII or message contents in analytics, no tracking for opted-out marketing where applicable. Funnel and cohort definitions documented with unit/SQL tests. No cross-account leakage.

## Delivery and quality gates
1. First audit current repo, existing notifications and Phase 5 flag behavior. Provide design, schema, API/event contracts, migration order, provider choices/tradeoffs, environment variables, cost/rate limits, and release/rollback plan.
2. Implement M6.1→M6.4 in small reviewable commits; preserve Phase 4 trust, Phase 5 discovery, subscription/Google Play, and existing booking/message APIs.
3. SQL migrations forward-only, RLS for every new public table, restricted `castly_api` ACLs; use an isolated local PostgreSQL fixture for tests. Do not touch production DB.
4. Java unit/integration, SQL/RLS negative tests, Expo component tests, Android physical-device test plan (permissions, background, cold start, deep links, token rotation, offline, multi-account). Test duplicate events, retries, quiet hours, blocked actors, unsubscribe and failure handling.
5. Add docs `docs/phase6/implementation-plan.md`, `docs/phase6/test-report.md`, `docs/phase6/release-checklist.md` and a draft PR with test evidence and explicit blockers. Feature flags default OFF and providers disabled. Never silently enable engagement, email or push.

## Acceptance example
Client describes a campaign → Phase 5 recommends eligible creators → client books one → creator receives one push and/or opted-in email → acceptance notifies client → accepted participants receive message notifications → consented reminders/digests work → aggregate funnel/retention analytics update. Private content, blocked creators and unauthorized accounts never leak.

Start with a repository audit and implementation plan, then code and tests on the feature branch. Report PR URL and outstanding owner decisions.
