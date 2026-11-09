# P3-M1 Subscription API Contract (draft, not deployed)

**Status:** API contract and client-side pure domain implementation only. The Spring endpoints below are not yet implemented. No billing, Google Play SKU, hosted migration, or payment activation has occurred.

Existing authoritative endpoint: `GET /api/v1/account/entitlements`, authenticated, read-only, scoped to the JWT user via restricted database role and RLS.

Proposed:
- `GET /api/v1/subscriptions/plans` public, returns plan code, title, features, purchase availability and Google Play product IDs when configured. Never hardcode unapproved prices.
- `GET /api/v1/subscriptions/me` authenticated, returns effective feature entitlements and expiry; calculate on server from authoritative grants.
- `POST /api/v1/subscriptions/google-play/verify` is **out of scope for M1** and must not be enabled until Google Play Developer API verification and purchase-token replay protection are implemented.

## Feature matrix
- Free: marketplace_access, standard_profile
- Creator Pro: Free plus premium_discovery, enhanced_portfolio
- Founding offer: eventual verified 12-month creator_pro entitlement, subject to approved verification and audited server-side grant.
- Purchases disabled until Play Console SKU and backend verification are ready.

## Security / rollout
- UI gating is advisory only. Paid capabilities must be enforced by Spring Boot and PostgreSQL RLS/authorized service operations.
- No `EXPO_PUBLIC_` secret, no client-side grant endpoint.
- Existing `subscription_entitlements` table is read-only to users and castly_api; future grants require separately approved least-privilege migration.
- Existing booking/messaging routes and notifications are untouched.
- Google Play digital subscription checkout must follow Play policy; Expo Go cannot exercise native billing, so use an appropriate development or Play test build.
- Treat `valid_until=null` as unbounded only for server-verified entitlements; never accept client-supplied grant records as authoritative.

## Acceptance still pending
1. Implement Spring endpoints and integration tests against restricted castly_api role.
2. Wire mobile subscription screen to authenticated server response.
3. Verify backend enforcement for every premium capability.
4. Approve pricing, Google Play product ID and founding-offer rules.
