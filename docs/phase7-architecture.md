# Phase 7 architecture assessment and decision record

## Baseline audit (2026-11-19)

Requested remote base `feat/milestone5-booking-messaging-notifications` resolved to `b86deb4` during fetch. Work is isolated in `feat/phase7-admin-phase8-readiness`. Other worktrees are untouched. This base contains Expo Router/React Native, Java 21 Spring Boot 3.5.7, JWT signature/issuer/audience/expiry verification, a restricted `castly_api` pool, transaction-local identity through `private.request_user_id()`, participant-only booking/messaging RLS, invoker in-app notifications and Phase 3 Google Play subscriptions. SQL initialization is disabled. Render/EAS configuration exists. There is no Next.js dashboard or administrative RBAC. Existing creator promotion review retains its Phase 3 reviewer roster. With the new admin flag off its behavior is unchanged; with it on an additional verified MFA/session gate is required. No reviewer membership is granted.

Fresh and reconciliation fixtures, Java HTTP/JWT tests, Node/components/browser/SQL tests exist. Several old browser runners assume `/tmp` or Chromium system paths; cross-platform conversion belongs to M8.1 and is not claimed complete here. New M7 runners use os.tmpdir and Playwright-managed browsers with an explicit executable override.

## Phase 6 dependency/readiness

Phase 6 branch `0596e4f` is separate, based on an integrated tree `1b37a65` absent from this base. Its report records local tests, not hosted/native/provider readiness. No Phase 4–6 code is merged as part of M7.1. M7.1 has no dependency on outbound providers. Future M7.2–4 must reconcile trust reports, suspensions, Phase 6 consented analytics and subscription provenance before implementing their adapters. Existing optional flags stay disabled. No production readiness or hosted equivalence is inferred.

## Proposed boundary

`admin-dashboard/`: separate Next.js App Router + TypeScript + Tailwind application. Supabase Auth is the identity authority. Password + existing TOTP factor challenge/verification takes place through a same-origin server BFF. Access/refresh tokens and the backend session ID are HttpOnly SameSite=Strict cookies; no browser token storage. The dashboard calls only its fixed-path BFF, which calls Spring REST. No PostgreSQL client or privileged Supabase key in Next. No external invitation email or initial administrator is provisioned by application startup.

Spring remains the authorization authority. All admins require signed, unexpired Supabase JWTs with AAL2, TOTP AMR and a Supabase session_id. Mutating high-risk actions additionally require an MFA authentication timestamp within five minutes. A short-lived DB session is bound to the JWT subject, Supabase session_id, principal version and one-hour expiry. Revocation/role change invalidate access on every request. Login/MFA is not inferred from client metadata. No privileged JWT role is issued.

Granular permissions map to fixed role definitions in a forward-only migration. An application-owned `admin_private` schema stores principals, an actor-only authorization projection, invitations, sessions, atomic minute limits and append-only audit records. The projection avoids recursive RLS when inspecting/updating other principals. Invoker triggers synchronize it and enforce immutable super admins, no self-change, bounded roles, invitation binding and mandatory safe audit entries. `castly_api` gains narrow column grants only on new admin tables; no blanket business reads/writes, auth schema USAGE, SECURITY DEFINER or memberships.

Admin business reads/operations are deferred to M7.2/M7.3; no generic SQL proxy, blanket booking transition or identity-document exposure exists. M7.4 will use consented aggregate sources, bounded preaggregation and verified billing facts; booking value is never platform revenue.

## Sitemap and milestones

M7.1 `/login`, `/login/mfa`, `/activate`, `/dashboard`, `/settings/admins`, `/audit`. Navigation shows future modules disabled pending approval. M7.2 `/users`, `/users/[id]`, `/users/verifications`, `/users/suspensions`. M7.3 `/bookings`, `/bookings/[id]`, `/reports`, `/moderation`, `/operations`. M7.4 `/analytics/users`, `/analytics/marketplace`, `/analytics/revenue`, `/analytics/subscriptions`.

Proceed in the requested order. M7.1 complete local code/UI/migrations/negative tests, then STOP for owner review. Do not begin M7.2, deploy, bootstrap, send invitations or apply hosted SQL. Phase 8 docs are preparation, not claims of completed Phase 8 security/performance/store validation.

## Risks

Owner approval required for hosted catalog reconciliation, initial bootstrap, actual Supabase TOTP enrollment/recovery/session revocation behavior, deployment origins, audit IP pseudonym key/retention and independent immutable audit anchoring. PostgreSQL owners can alter schema: append-only application grants are not a defense against a malicious DB owner. Existing evidence that remote base moved back from prior integration must be resolved separately before merging Phase 4–6. In-process BFF login throttling is defense in depth; enforce provider/edge rate limits before exposure. iOS billing and million-user capacity are unverified.
