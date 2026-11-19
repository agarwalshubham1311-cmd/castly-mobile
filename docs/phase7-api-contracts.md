# M7.1 REST contracts

All routes are under `/api/v1/admin`. Existing Supabase Bearer JWT verification applies. Admin routes are feature-gated off by default. Only session creation and invitation activation omit the additional `X-Castly-Admin-Session` header; both still require a verified fresh TOTP AAL2 JWT. No arbitrary redirect/callback URL is accepted.

- POST `/sessions` => one-hour session ID, expiry, current roles/permissions; detects concurrent session fanout, rate limited.
- GET `/me` => principal ID, roles, permissions, session expiry.
- DELETE `/sessions/{id}` body `{reason}` => revoke own session. For another principal, PATCH roles/state (even unchanged) with admins.manage + recent MFA to increment the security version and invalidate every old session.
- GET `/principals?after=<uuid>&limit=25` => keyset rows, maximum 50; admins.manage.
- PATCH `/principals/{id}` body `{roles,state,expected_version,reason}` => admins.manage + recent MFA; optimistic conflict; no self/super changes.
- POST `/invitations` body `{user_id,roles,reason}` => admins.manage + recent MFA; returns invitation ID/token once; 24h expiry; existing Supabase account only.
- POST `/invitations/activate` body `{token,reason}` => exact invitee + recent MFA; single-use transaction, no implicit session.
- GET `/sessions?after=<uuid>&limit=25` => own sessions only; other principals are managed through version invalidation, without exposing their session metadata.
- GET `/audit?before=<bigint>&limit=25` => descending keyset, safe metadata; audit.read.

Responses are snake_case; 400 invalid, 401 invalid bearer, 403 permission/MFA/session denial, 404 disabled or missing resource, 409 stale version/already-used invitation, 429 minute quota, 503 unavailable DB. Admin body capped at 16 KiB. Correlation response header `X-Request-ID` is generated server-side. Fixed same-origin Next proxy supports only these paths/methods and injects server-cookie session ID. No automatic database migration, invitation delivery or super-admin bootstrap endpoint exists.

Legacy `/api/v1/admin/subscriptions/promotions` routes are deliberately absent from the Next proxy. With CASTLY_ADMIN_ENABLED=false, they retain their existing restricted reviewer behavior. Once enabled, Spring additionally requires this admin session + verified MFA (subscriptions.read for listing, admins.manage and recent MFA for decisions) while keeping the existing subscription-reviewer roster and source audit ledger. The server performs the delegated check and original action in the same identity-bound transaction; new roles do not grant reviewer membership. New management does not silently bypass a historical privileged path.
