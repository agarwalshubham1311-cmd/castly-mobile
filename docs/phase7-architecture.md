# Phase 7 architecture assessment and decision record

## Baseline audit (2026-11-19)

The authoritative fetched head of `feat/milestone5-booking-messaging-notifications` is **3c12b515f5ba2e6ef67d0089d0fd49ea6c99a175** (Phase6 merge). Initial inspection mistakenly used a stale remote-tracking ref `b86deb4`: this clone fetches only main by default. The error was corrected by inspecting FETCH_HEAD/ls-remote, merging the actual base into the isolated feature branch without changing other branches or rewriting history, preserving all existing Phase4–6 code, and rerunning integrated checks. Results below refer to the corrected tree, not the earlier Phase3-only subset.

The integrated repository contains Expo Router/React Native, Java21 Spring Boot3.5.7, signature/issuer/audience/expiry validation, restricted castly_api with transaction-local private.request_user_id(), participant-only booking/conversation/message RLS, invoker in-app notifications, Google Play subscriptions, Phase4 verification/reviews/reporting/disputes/private evidence/trust, Phase5 discovery/matching and Phase6 push/email/engagement/analytics. Render/EAS configuration exists; SQL auto-init is disabled. There was no Next dashboard or unified admin RBAC. Existing subscription/trust/analytics reviewer rosters remain independently required; M7.1 adds opt-in session/MFA fencing to their administrative HTTP routes without granting membership.

Fresh and reconciliation fixtures, Java HTTP/JWT tests, Node/components/browser/SQL tests exist. Several old browser runners assume `/tmp` or Chromium system paths; cross-platform conversion belongs to M8.1 and is not claimed complete here. New M7 runners use os.tmpdir and Playwright-managed browsers with an explicit executable override.

## Phase 6 dependency/readiness

Phase6 is now included through merge3c12b51. Its reviewed forward SQL uses the existing private identity helper, consented transactional outbox, separate restricted worker and disabled-by-default Expo/Resend adapters. Reports record local security/regression tests, not hosted/native/delivery readiness. No hosted catalog, provider credentials, verified-mailbox policy or native delivery rollout has been inspected/activated. M7.1 does not enable any Phase4–6 flag/provider, provision worker/admin users or expose new business analytics. Future M7.2–4 must reuse the integrated trust/discovery/consented analytics and verified subscription sources under their existing RLS and approval gates.

## Proposed boundary

`admin-dashboard/`: separate Next.js App Router + TypeScript + Tailwind application. Supabase Auth is the identity authority. Password + existing TOTP factor challenge/verification takes place through a same-origin server BFF. Access/refresh tokens and the backend session ID are HttpOnly SameSite=Strict cookies; no browser token storage. The dashboard calls only its fixed-path BFF, which calls Spring REST. No PostgreSQL client or privileged Supabase key in Next. No external invitation email or initial administrator is provisioned by application startup.

Spring remains the authorization authority. All admins require signed, unexpired Supabase JWTs with AAL2, TOTP AMR and a Supabase session_id. Mutating high-risk actions additionally require an MFA authentication timestamp within five minutes. A short-lived DB session is bound to the JWT subject, Supabase session_id, principal version and one-hour expiry. Revocation/role change invalidate access on every request. Login/MFA is not inferred from client metadata. No privileged JWT role is issued.

Granular permissions map to fixed role definitions in a forward-only migration. An application-owned `admin_private` schema stores principals, an actor-only authorization projection, invitations, sessions, atomic minute limits and append-only audit records. The projection avoids recursive RLS when inspecting/updating other principals. Invoker triggers synchronize it and enforce immutable super admins, no self-change, bounded roles, invitation binding and mandatory safe audit entries. `castly_api` gains narrow column grants only on new admin tables; no blanket business reads/writes, auth schema USAGE, SECURITY DEFINER or memberships.

Admin business reads/operations are deferred to M7.2/M7.3; no generic SQL proxy, blanket booking transition or identity-document exposure exists. M7.4 will use consented aggregate sources, bounded preaggregation and verified billing facts; booking value is never platform revenue.

## Sitemap and milestones

M7.1 `/login`, `/login/mfa`, `/activate`, `/dashboard`, `/settings/admins`, `/audit`. Navigation shows future modules disabled pending approval. M7.2 `/users`, `/users/[id]`, `/users/verifications`, `/users/suspensions`. M7.3 `/bookings`, `/bookings/[id]`, `/reports`, `/moderation`, `/operations`. M7.4 `/analytics/users`, `/analytics/marketplace`, `/analytics/revenue`, `/analytics/subscriptions`.

Proceed in the requested order. M7.1 complete local code/UI/migrations/negative tests, then STOP for owner review. Do not begin M7.2, deploy, bootstrap, send invitations or apply hosted SQL. Phase 8 docs are preparation, not claims of completed Phase 8 security/performance/store validation.

## Risks

Owner approval required for hosted catalog reconciliation, initial bootstrap, actual Supabase TOTP enrollment/recovery/session revocation behavior, deployment origins, audit IP pseudonym key/retention and independent immutable audit anchoring. PostgreSQL owners can alter schema: append-only application grants are not a defense against a malicious DB owner. The corrected fetch/base audit must be reviewed; future fetches should explicitly inspect FETCH_HEAD or update the requested tracking ref instead of assuming this clone tracks every branch. In-process BFF login throttling is defense in depth; enforce provider/edge rate limits before exposure. iOS billing and million-user capacity are unverified.
