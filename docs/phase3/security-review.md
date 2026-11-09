# Phase 3 security review

Reviewed locally on 2026-11-09. Release is blocked pending native/Play integration evidence and approved infrastructure configuration.

## Trust boundaries

Supabase JWT verification remains unchanged for business routes. The JWT subject becomes transaction-local identity through `ActorDatabase` and `private.request_user_id()`. The restricted `castly_api` role remains non-owner, without SUPERUSER, BYPASSRLS, role memberships or schema creation. No service-role credentials or SECURITY DEFINER functions were added. Hosted SQL and secrets were not changed.

New public tables have RLS and explicit revokes from PUBLIC/anon/authenticated. The API receives column-scoped INSERT/UPDATE grants; IDs and audit timestamps use database defaults. Ordinary self-service transactions cannot grant entitlements or mutate orders. Verified purchase processing sets transaction-local `castly.subscription_verified=yes`; reviewer grants require an owner-provisioned `private.subscription_admins` row. This GUC is a defense against accidental unscoped server SQL, **not cryptographic database evidence**: a party possessing backend SQL credentials can set it. The trusted payment-verification service and protected backend credentials remain the payment trust boundary. Mobile clients have neither SQL credentials nor subscription table writes.

Reviewer authorization never uses user-controlled JWT metadata. Reviewers cannot review their own claim. Pending-to-terminal decisions are immutable, row-locked and idempotent. Unique user and canonical post URL constraints prevent repeated membership awards. Canonical YouTube watch/shorts/short-link aliases converge on video identity. Manual moderation is required; the server never fetches arbitrary social URLs or claims to assess engagement.

## Provider verification

Google OAuth and Developer API endpoints are hardcoded HTTPS origins with normal certificate verification, no redirects, finite timeouts and bounded responses. Service-account keys stay in a backend-mounted file. Configuration requires readable service-account JSON and a valid >=2048-bit RSA key. This local validation does not prove Play IAM permissions or product availability.

Verification requires the configured package endpoint, enabled Creator Pro product/base plan, one supported line item, HMAC account association, known lifecycle state and valid expiry. Active/grace/cancelled-but-unexpired states qualify; pending, hold, paused and expired states do not. Renewals update one grant. Provider transaction IDs and token hashes are unique. Raw purchase tokens are transient and are never persisted, included in history/audit responses, or logged by the module. Linked tokens are retired permanently when replacement is eligible, and cross-account token ownership is rejected. Superseded-token RTDN messages are acknowledged without reactivating old access.

RTDN has its own OAuth resource-server filter chain. It verifies RS256 against Google's pinned JWKS, exact issuer, configured audience, approved email, boolean `email_verified`, expiration and recent issued time **before controller delivery**. Supabase access tokens do not authorize RTDN. Message ID plus payload digest protects replay; receipt/event/order/grant mutations commit together. Provider failures roll back normal event receipts for Pub/Sub retry. Authenticated revocation/voided events revoke an already-associated purchase even if the Developer API is unavailable. A terminal revoked order cannot be revived by a stale client restore. No raw provider payload is stored.

## Entitlements and mobile

Effective grants are evaluated at database time; expired/future/revoked grants never authorize access. Overlapping promotional and paid grants remain independent. The old entitlement response keeps id/user_id/created_at and reflects effective grants when enabled. Discovery exposes only server-projected eligibility with expiry, never payment or moderation data. Portfolio creates use the effective configurable limit under an account lock. Seed limits are NULL to preserve existing Free behavior; finite limits require product/rollout approval.

Mobile native callbacks request verification; they never mutate entitlement state. Pending purchases are not acknowledged by the client. Backend verification and acknowledgement precede transaction finishing. Duplicate callbacks, restored purchases and account changes are handled explicitly. Native errors map to fixed user-safe text. AuthGuard account-key remounts and generation checks prevent previous-account async responses leaking into the new screen. Expo Go/web use unavailable native billing while membership/promotion screens remain usable.

## Tested and remaining operational risks

Automated tests cover RLS/ownership, unscoped grant/order denial, concurrent paid verification and promotion approval, duplicate processing, audit rollback, expiry/overlap, product/account mismatch, signed Google-filter rejection, provider HTTP contracts, native adapter states and browser workflows. See implementation report for exact executed counts.

Production controls still required: restrict backend secret access; retain/back up the stable account-association key (rotation requires a versioned account-ID migration); approve catalog IDs/limits; verify Play IAM and license testers; configure authenticated Pub/Sub push, retries, dead-letter monitoring and retention; test real acknowledgement/renewal/refund/replacement on devices. Tokens are intentionally not stored for unattended polling, so missed notifications recover through Pub/Sub retry/monitoring or user Restore Purchases. Local deterministic provider fixtures are not evidence of live billing readiness. No independent external penetration test was performed.
