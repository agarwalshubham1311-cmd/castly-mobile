# Castly Phase 3 — Subscriptions & Monetization (Draft v0.1)

Status: design and implementation planning only. No payment processing, hosted migrations, notification enablement, or production writes authorized by this document.

## Goals
- Subscription revenue instead of booking commissions.
- Creator founding offer: verified promotional post grants 12 months of membership.
- Server-authoritative entitlements; clients never grant paid features.
- Preserve booking/messaging and restricted `castly_api` RLS behavior.
- Develop independently of Expo EAS preview APK testing.

## Current baseline
- `public.subscription_entitlements(id,user_id,entitlement,valid_until,created_at)` exists.
- Read-only entitlement access for `castly_api` exists; no ordinary client entitlement writes.
- Subscription UI is informational; no checkout or payment backend exists.
- Notifications remain disabled; do not apply notification migrations.

## Milestones
### P3-M1: Product catalog and entitlement contracts
- Agree plans, INR prices, renewal/cancellation terms, and exact feature gates.
- Define read-only `GET /api/subscriptions/me` and `GET /api/subscriptions/plans`.
- Add API DTOs and mock/test fixtures; no payment initiation.
- Unit tests for free, active, expired, revoked, and promotional memberships.

### P3-M2: Founding creator offer
- Creator submits qualifying promotional video/post URL.
- Verification goes to admin review (not automatic trust in social URLs).
- One approved claim per eligible creator; server-side 12-month grant.
- Idempotent approval and complete audit history; rejection and appeal flow.
- No direct client writes to entitlement tables.

### P3-M3: Billing integration (provider decision required)
- Choose Google Play Billing for applicable in-app digital purchases on Play, and separately assess compliant web billing for other channels.
- Backend validates purchase tokens/receipts with provider, processes signed notifications/webhooks, and handles replay/idempotency.
- Only verified provider events grant/renew/revoke entitlements.
- Handle grace periods, refunds, cancellations, chargebacks, expiration, and subscription restore.
- Do not embed billing secret keys in EXPO_PUBLIC_* variables.

### P3-M4: UI and release QA
- Plans and subscription status screens; founding offer claim workflow.
- Feature gating in API plus UI; tests for account switching and expired plans.
- Production readiness: audit logs, support/refund policy, reconciliation and monitoring.

## Suggested logical data model (not yet a migration)
- subscription_plans: plan code, price/currency, period, features, status.
- subscription_orders: internal order ID, account ID, provider, provider IDs, status, timestamps.
- subscription_events: deduplicated provider event ID, payload hash, received/processed time, result.
- subscription_grants: account, entitlement, source, effective/expiry dates, revoked_at, audit actor.
- promotion_claims: account, submitted URL, verification state, reviewer, timestamps, unique eligibility constraint.

The existing `subscription_entitlements` table should be reconciled forward-only; do not replace or edit historical migrations.

## Security invariants
- All mutations require server-side authorization and audit.
- Preserve `castly_api` least privilege, per-user identity, and RLS.
- Never grant a membership based solely on client response, redirect, or unverified receipt.
- Idempotent grant and webhook handlers.
- Keep `CASTLY_NOTIFICATIONS_ENABLED=false`.
- No hosted SQL changes without explicit approval.

## Parallel execution
Track A (APK): EAS preview configuration, cloud build, device smoke tests.
Track B (Phase 3): catalog/entitlement contracts, promotional flow design, billing-provider decision, implementation and tests.
Do not couple Phase 3 unfinished changes to the Android preview release branch.

## Decisions required
1. Initial paid tiers and INR pricing.
2. Creator founding-offer eligibility and verification criteria.
3. Android Play-distributed billing versus external/web checkout strategy.
4. Whether clients pay subscriptions in v1, or only creators.

## Acceptance criteria for M1
- Versioned API contract and plan matrix reviewed.
- Existing entitlement behavior and RLS verified in tests.
- No payment keys in mobile builds.
- No production schema changes, no notifications, and no booking regressions.
